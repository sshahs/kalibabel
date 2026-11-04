package email.sshah.kalibabel.providers.impl

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import email.sshah.kalibabel.data.Language
import email.sshah.kalibabel.data.Languages
import email.sshah.kalibabel.providers.Capability
import email.sshah.kalibabel.providers.ModelInfo
import email.sshah.kalibabel.providers.Provider
import email.sshah.kalibabel.providers.ProviderContext
import email.sshah.kalibabel.providers.SttEngine
import email.sshah.kalibabel.providers.SttEvent
import email.sshah.kalibabel.providers.SttRequest
import email.sshah.kalibabel.providers.TranslationDelta
import email.sshah.kalibabel.providers.TranslationEngine
import email.sshah.kalibabel.providers.TranslationRequest
import email.sshah.kalibabel.providers.TtsEngine
import email.sshah.kalibabel.providers.TtsOutput
import email.sshah.kalibabel.providers.TtsRequest
import email.sshah.kalibabel.providers.VoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** ML Kit on-device translation: offline after a one-time ~30 MB download per language. */
class OnDeviceTranslator {
    private val translators = ConcurrentHashMap<String, Translator>()

    fun mlKitCode(l: Language): String? = TranslateLanguage.fromLanguageTag(l.code)

    fun isSupported(l: Language) = mlKitCode(l) != null

    private fun translator(from: Language, to: Language): Translator {
        val src = mlKitCode(from) ?: throw IllegalArgumentException("${from.englishName} is not available for offline translation")
        val dst = mlKitCode(to) ?: throw IllegalArgumentException("${to.englishName} is not available for offline translation")
        return translators.getOrPut("$src>$dst") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        }
    }

    /** Languages of the pair whose offline pack still has to be downloaded. */
    suspend fun missingPacks(from: Language, to: Language): List<Language> = listOf(from, to).filter { l ->
        val code = mlKitCode(l) ?: return@filter false
        runCatching {
            !RemoteModelManager.getInstance().isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()
        }.getOrDefault(false)
    }

    suspend fun translate(text: String, from: Language, to: Language): String {
        if (from.code == to.code) return text
        val t = translator(from, to)
        t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return t.translate(text).await()
    }

    /** Pre-downloads the language packs for a pair (so the first live translation is instant). */
    suspend fun download(a: Language, b: Language) {
        translator(a, b).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        translator(b, a).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }
}

/** On-device language identification restricted to the conversation pair. */
class LanguageDetector {
    private val client by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.05f).build())
    }

    /** Returns whichever of [a] / [b] the text is more likely written in, or null if unsure. */
    suspend fun pick(text: String, a: Language, b: Language): Language? = runCatching {
        val candidates = client.identifyPossibleLanguages(text).await()
        val scoreA = candidates.filter { Languages.matches(a, it.languageTag) }.maxOfOrNull { it.confidence } ?: 0f
        val scoreB = candidates.filter { Languages.matches(b, it.languageTag) }.maxOfOrNull { it.confidence } ?: 0f
        when {
            scoreA == 0f && scoreB == 0f -> null
            scoreA >= scoreB -> a
            else -> b
        }
    }.getOrNull()
}

/** Shared Android TextToSpeech instance (initialisation is slow, so it is kept alive). */
class SystemTts(private val app: Context) {
    private var tts: TextToSpeech? = null
    private var enginePackage: String? = null
    private val mutex = Mutex()

    suspend fun get(engine: String?): TextToSpeech = mutex.withLock {
        val wanted = engine?.takeIf { it.isNotBlank() }
        tts?.let { if (wanted == enginePackage) return@withLock it }
        tts?.shutdown()
        val ready = CompletableDeferred<Int>()
        val created = withContext(Dispatchers.Main) {
            TextToSpeech(app, { status -> ready.complete(status) }, wanted)
        }
        val status = ready.await()
        if (status != TextToSpeech.SUCCESS) {
            created.shutdown()
            throw IllegalStateException("Text-to-speech engine failed to initialise")
        }
        tts = created
        enginePackage = wanted
        created
    }

    suspend fun speak(text: String, language: Language, voiceName: String?, speed: Float, engine: String?) {
        val t = get(engine)
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resumeWithException(IllegalStateException("Speech synthesis failed"))
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId == id && cont.isActive) cont.resumeWithException(IllegalStateException("Speech synthesis failed ($errorCode)"))
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            val voice = voiceName?.let { n -> runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == n } }
            if (voice != null) t.voice = voice else t.language = language.locale
            t.setSpeechRate(speed)
            cont.invokeOnCancellation { runCatching { t.stop() } }
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
            if (t.speak(text, TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resumeWithException(IllegalStateException("Speech synthesis was rejected"))
            }
        }
    }

    suspend fun voices(language: Language?, engine: String?): List<VoiceInfo> {
        val t = get(engine)
        val all = runCatching { t.voices }.getOrNull().orEmpty()
        return all.filter { v -> language == null || v.locale.language == language.locale.language }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
            .map { v ->
                val traits = listOfNotNull(
                    v.locale.displayName,
                    if (v.isNetworkConnectionRequired) "network" else "offline",
                    if (v.quality >= 400) "high quality" else null,
                )
                VoiceInfo(v.name, v.name, traits.joinToString(" · "), listOf(v.locale.toLanguageTag()))
            }
    }

    suspend fun engines(): List<ModelInfo> {
        val t = get(enginePackage)
        return listOf(ModelInfo("", "System default", "Uses the engine chosen in Android settings")) +
            t.engines.map { ModelInfo(it.name, it.label, it.name) }
    }
}

class AndroidProvider(
    ctx: ProviderContext,
    private val systemTts: SystemTts,
    private val onDevice: OnDeviceTranslator,
    private val detector: LanguageDetector,
) : Provider(ctx) {

    // ------------------------------------------------------------------ translation (ML Kit)

    override val translation: TranslationEngine = object : TranslationEngine {
        override val isLlm = false

        override fun translate(request: TranslationRequest): Flow<TranslationDelta> = flow {
            var source = request.source
            var target = request.target
            if (request.bidirectional || source == null || target == null) {
                val detected = detector.pick(request.text, request.languageA, request.languageB) ?: request.languageA
                source = detected
                target = if (detected == request.languageA) request.languageB else request.languageA
                emit(TranslationDelta.Target(target))
            }
            val missing = onDevice.missingPacks(source, target)
            if (missing.isNotEmpty()) {
                emit(
                    TranslationDelta.Status(
                        "Downloading the offline ${missing.joinToString(" & ") { it.englishName }} pack (one-time, ~30 MB)…",
                    ),
                )
            }
            emit(TranslationDelta.Text(onDevice.translate(request.text, source, target)))
        }
    }

    // ------------------------------------------------------------------ STT (SpeechRecognizer)

    override val stt: SttEngine = object : SttEngine {
        override val selfCapture = true

        override fun supportsBatch(model: String) = false

        override fun listen(request: SttRequest, stop: CompletableDeferred<Unit>): Flow<SttEvent> = callbackFlow {
            val app = ctx.app
            if (!SpeechRecognizer.isRecognitionAvailable(app)) {
                throw IllegalStateException("No speech recognition service is installed on this device")
            }
            val recognizer = if (request.model == "on-device" && Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            } else {
                SpeechRecognizer.createSpeechRecognizer(app)
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, request.silenceMs.toLong())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, request.silenceMs.toLong())
                if (request.model == "on-device") putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                val lang = request.language
                if (lang != null) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
                } else if (Build.VERSION.SDK_INT >= 34) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        ArrayList(request.candidates.map { it.tag }),
                    )
                    request.candidates.firstOrNull()?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it.tag) }
                }
            }
            var stopped = false

            fun restartOrClose() {
                if (request.continuous && !stopped) {
                    launch {
                        request.awaitQuiet?.invoke()
                        if (!stopped) recognizer.startListening(intent) else close()
                    }
                } else {
                    close()
                }
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() {
                    trySend(SttEvent.SpeechStarted)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    trySend(SttEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    trySend(SttEvent.SpeechEnded)
                }

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartOrClose()
                        SpeechRecognizer.ERROR_CLIENT -> close()
                        else -> close(IllegalStateException("Speech recognizer: ${errorText(error)}"))
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    val lang = if (Build.VERSION.SDK_INT >= 34) results?.getString(SpeechRecognizer.DETECTED_LANGUAGE) else null
                    if (!text.isNullOrEmpty()) trySend(SttEvent.Final(text, lang))
                    restartOrClose()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) trySend(SttEvent.Partial(text))
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(intent)
            launch {
                stop.await()
                stopped = true
                recognizer.stopListening()
            }
            awaitClose {
                runCatching { recognizer.cancel() }
                recognizer.destroy()
            }
        }.flowOn(Dispatchers.Main)
    }

    // ------------------------------------------------------------------ TTS

    override val tts: TtsEngine = object : TtsEngine {
        override suspend fun synthesize(request: TtsRequest): TtsOutput = TtsOutput.SelfPlayed {
            systemTts.speak(request.text, request.language, request.voice, request.speed, request.model)
        }
    }

    // ------------------------------------------------------------------ catalogs

    override suspend fun listModels(capability: Capability): List<ModelInfo> = when (capability) {
        Capability.STT -> buildList {
            add(ModelInfo("default", "System recognizer", "Google / OEM speech service; may use the network"))
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx.app)) {
                add(ModelInfo("on-device", "On-device recognizer", "Fully offline (download the language in Android settings)"))
            }
        }
        Capability.TRANSLATION -> listOf(ModelInfo("mlkit", "ML Kit on-device", "Offline · ~59 languages · download once per language"))
        Capability.TTS -> systemTts.engines()
    }

    override suspend fun listVoices(model: String?, language: Language?): List<VoiceInfo> = systemTts.voices(language, model)

    override suspend fun prewarm(models: Map<Capability, String>) {
        runCatching { systemTts.get(models[Capability.TTS]) }
    }

    override suspend fun test(): String {
        val app = ctx.app
        val sr = SpeechRecognizer.isRecognitionAvailable(app)
        val od = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        val engines = runCatching { systemTts.engines().size - 1 }.getOrDefault(0)
        return "Speech recognizer: ${if (sr) "yes" else "missing"} · on-device STT: ${if (od) "yes" else "no"} · TTS engines: $engines"
    }

    companion object {
        fun errorText(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network error"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "server error"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language pack not downloaded"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
            else -> "error $code"
        }
    }
}
