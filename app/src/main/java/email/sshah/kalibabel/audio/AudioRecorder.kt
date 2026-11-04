package email.sshah.kalibabel.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import email.sshah.kalibabel.data.MicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import kotlin.math.max

/** Captures 16 kHz mono 16-bit PCM in small frames (20 ms by default) for low-latency streaming. */
class AudioRecorder(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun frames(
        source: MicSource,
        noiseSuppression: Boolean,
        frameMs: Int = 20,
        sampleRate: Int = Pcm.SAMPLE_RATE,
    ): Flow<ShortArray> = flow {
        if (!hasPermission()) throw SecurityException("Microphone permission not granted")
        val frameSamples = sampleRate * frameMs / 1000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audioSource = when (source) {
            MicSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            MicSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            MicSource.MIC -> MediaRecorder.AudioSource.MIC
        }
        val record = AudioRecord(
            audioSource,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, frameSamples * 2 * 10),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("Microphone is unavailable (is another app recording?)")
        }
        val effects = mutableListOf<AudioEffect>()
        if (noiseSuppression && NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true; effects += it } }
        }
        if (source == MicSource.VOICE_COMMUNICATION && AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true; effects += it } }
        }
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val frame = ShortArray(frameSamples)
                var offset = 0
                while (offset < frameSamples) {
                    val n = record.read(frame, offset, frameSamples - offset)
                    if (n < 0) throw IOException("Microphone read failed ($n)")
                    offset += n
                }
                emit(frame)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            effects.forEach { runCatching { it.release() } }
        }
    }.flowOn(Dispatchers.IO)
}
