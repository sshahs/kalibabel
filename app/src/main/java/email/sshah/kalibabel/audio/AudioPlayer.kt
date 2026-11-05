package email.sshah.kalibabel.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import email.sshah.kalibabel.data.ErrorLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** A unit of synthesized speech, already being produced in the background. */
sealed interface SpeechItem {
    class Pcm(val sampleRate: Int, val data: ReceiveChannel<ByteArray>) : SpeechItem

    class Wav(val data: ReceiveChannel<ByteArray>) : SpeechItem

    class Encoded(val mimeType: String, val data: ReceiveChannel<ByteArray>) : SpeechItem

    class SelfPlayed(val play: suspend () -> Unit) : SpeechItem
}

/** What [SpeechQueue] plays through; [AudioPlayer] in the app. */
interface SpeechOutput {
    /** Plays one item; [onFirstAudio] fires when the first samples reach the audio device. */
    suspend fun play(item: SpeechItem, onFirstAudio: () -> Unit)

    /** Waits until everything written so far has been heard. */
    suspend fun drain()

    /** Stops playback immediately. */
    fun stop()
}

/**
 * Low-latency audio output. PCM is written straight into a streaming [AudioTrack] as network
 * chunks arrive, so playback starts with the first ~100 ms of audio instead of the whole file.
 */
class AudioPlayer(private val context: Context) : SpeechOutput {
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var framesWritten = 0L
    private var mediaPlayer: MediaPlayer? = null

    override suspend fun play(item: SpeechItem, onFirstAudio: () -> Unit) {
        when (item) {
            is SpeechItem.Pcm -> playPcm(item.sampleRate, item.data, onFirstAudio)
            is SpeechItem.Wav -> playWav(item.data, onFirstAudio)
            is SpeechItem.Encoded -> playEncoded(item.mimeType, item.data, onFirstAudio)
            is SpeechItem.SelfPlayed -> {
                drain()
                onFirstAudio()
                item.play()
            }
        }
    }

    private suspend fun playPcm(sampleRate: Int, data: ReceiveChannel<ByteArray>, onFirstAudio: () -> Unit) =
        withContext(Dispatchers.IO) {
            var first = true
            var carry: Byte? = null
            for (chunk in data) {
                if (!coroutineContext.isActive) break
                var bytes = chunk
                carry?.let { bytes = byteArrayOf(it) + bytes; carry = null }
                if (bytes.size % 2 == 1) {
                    carry = bytes.last()
                    bytes = bytes.copyOf(bytes.size - 1)
                }
                if (bytes.isEmpty()) continue
                val t = ensureTrack(sampleRate)
                if (first) {
                    first = false
                    onFirstAudio()
                }
                var off = 0
                while (off < bytes.size && coroutineContext.isActive) {
                    val n = t.write(bytes, off, bytes.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) break
                    off += n
                }
                framesWritten += off / 2
            }
        }

    private suspend fun playWav(data: ReceiveChannel<ByteArray>, onFirstAudio: () -> Unit) {
        val parser = WavStreamParser()
        val pcm = Channel<ByteArray>(Channel.UNLIMITED)
        var rate: Int? = null
        // Read until the header is parsed, then hand the rest to the PCM path.
        for (chunk in data) {
            val out = parser.push(chunk)
            if (out.isNotEmpty() || parser.sampleRate != null) {
                rate = parser.sampleRate ?: 24_000
                if (out.isNotEmpty()) pcm.send(out)
                break
            }
        }
        val sampleRate = rate ?: return
        coroutineScope {
            launch {
                for (chunk in data) pcm.send(parser.push(chunk))
                pcm.close()
            }
            playPcm(sampleRate, pcm, onFirstAudio)
        }
    }

    private suspend fun playEncoded(mimeType: String, data: ReceiveChannel<ByteArray>, onFirstAudio: () -> Unit) {
        val ext = when {
            "mpeg" in mimeType || "mp3" in mimeType -> "mp3"
            "ogg" in mimeType || "opus" in mimeType -> "ogg"
            "aac" in mimeType -> "aac"
            "flac" in mimeType -> "flac"
            else -> "bin"
        }
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("tts", ".$ext", context.cacheDir).also { f ->
                f.outputStream().use { out -> for (chunk in data) out.write(chunk) }
            }
        }
        try {
            drain()
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val mp = MediaPlayer()
                    mediaPlayer = mp
                    cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
                    try {
                        mp.setAudioAttributes(attributes())
                        mp.setDataSource(file.absolutePath)
                        mp.setOnPreparedListener { onFirstAudio(); it.start() }
                        mp.setOnCompletionListener {
                            it.release()
                            mediaPlayer = null
                            if (cont.isActive) cont.resume(Unit)
                        }
                        mp.setOnErrorListener { p, what, extra ->
                            p.release()
                            mediaPlayer = null
                            if (cont.isActive) cont.resumeWithException(IllegalStateException("Playback failed ($what/$extra)"))
                            true
                        }
                        mp.prepareAsync()
                    } catch (e: Exception) {
                        mp.release()
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun ensureTrack(sampleRate: Int): AudioTrack {
        track?.let { if (trackRate == sampleRate) return it }
        releaseTrack()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf * 2, sampleRate / 5 * 2)) // >= 200 ms
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        t.play()
        track = t
        trackRate = sampleRate
        framesWritten = 0
        return t
    }

    /** Waits until everything written to the track has actually been played. */
    override suspend fun drain() {
        val t = track ?: return
        val deadline = System.currentTimeMillis() + 30_000
        while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
            val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= framesWritten || t.playState != AudioTrack.PLAYSTATE_PLAYING) break
            delay(15)
        }
    }

    /** Immediately silences everything. */
    override fun stop() {
        track?.let {
            runCatching {
                it.pause()
                it.flush()
                it.play()
            }
        }
        framesWritten = 0
        mediaPlayer?.let { runCatching { it.stop(); it.release() } }
        mediaPlayer = null
    }

    fun release() {
        stop()
        releaseTrack()
    }

    private fun releaseTrack() {
        track?.let { runCatching { it.stop(); it.release() } }
        track = null
        trackRate = 0
        framesWritten = 0
    }

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

/**
 * Plays speech strictly in conversation order while letting every turn synthesize in parallel:
 * a turn reserves a [Slot] the moment it starts, fills it whenever its audio is ready, and the
 * queue plays slots one after another.
 *
 * [stopAll] mutes everything queued so far (downloads are aborted) without disturbing the
 * producers, so in-flight translations still finish on screen.
 */
class SpeechQueue(private val scope: CoroutineScope, private val player: SpeechOutput) {
    class Slot(
        val turnId: Long,
        val onFirstAudio: () -> Unit,
        val onFinished: () -> Unit,
    ) {
        val items = Channel<SpeechItem>(Channel.UNLIMITED)

        @Volatile
        var muted = false

        fun close() {
            items.close()
        }
    }

    private val slots = Channel<Slot>(Channel.UNLIMITED)
    private val pending = java.util.concurrent.CopyOnWriteArrayList<Slot>()
    private val _speakingTurn = MutableStateFlow<Long?>(null)

    @Volatile
    private var currentPlay: Job? = null

    /** Turn currently being spoken, or null when silent. */
    val speakingTurn: StateFlow<Long?> = _speakingTurn.asStateFlow()

    val isSpeaking: Boolean get() = _speakingTurn.value != null

    init {
        scope.launch(Dispatchers.Default) { consume() }
    }

    fun reserve(turnId: Long, onFirstAudio: () -> Unit = {}, onFinished: () -> Unit = {}): Slot {
        val slot = Slot(turnId, onFirstAudio, onFinished)
        pending += slot
        slots.trySend(slot)
        return slot
    }

    /** Silences current and queued speech. */
    fun stopAll() {
        pending.forEach { it.muted = true }
        currentPlay?.cancel()
        player.stop()
        _speakingTurn.value = null
    }

    private suspend fun consume() = coroutineScope {
        for (slot in slots) {
            var first = true
            try {
                for (item in slot.items) {
                    if (slot.muted) {
                        discard(item)
                        continue
                    }
                    _speakingTurn.value = slot.turnId
                    val job = launch {
                        // A failure must stay inside this item: if it escaped the child it would
                        // cancel consume() and crash the app (e.g. a TTS socket dropping mid-reply).
                        try {
                            player.play(item) {
                                if (first) {
                                    first = false
                                    slot.onFirstAudio()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Playback for turn ${slot.turnId} interrupted", e)
                            // Network drops are logged where the audio was downloaded; this is the player itself.
                            if (e !is java.io.IOException) ErrorLog.warning("Playback", e.toString(), e)
                        }
                    }
                    currentPlay = job
                    try {
                        job.join()
                    } finally {
                        currentPlay = null
                    }
                    if (job.isCancelled && !slot.muted) discard(item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Speech for turn ${slot.turnId} failed", e)
            } finally {
                pending.remove(slot)
                slot.onFinished()
            }
            if (pending.isEmpty()) {
                player.drain()
                if (pending.isEmpty()) _speakingTurn.value = null
            }
        }
    }

    private companion object {
        const val TAG = "SpeechQueue"
    }

    private fun discard(item: SpeechItem) {
        when (item) {
            is SpeechItem.Pcm -> item.data.cancel()
            is SpeechItem.Wav -> item.data.cancel()
            is SpeechItem.Encoded -> item.data.cancel()
            is SpeechItem.SelfPlayed -> Unit
        }
    }
}
