package email.sshah.kalibabel.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Pcm {
    const val SAMPLE_RATE = 16_000

    fun toBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(samples)
        return buf.array()
    }

    fun concat(chunks: List<ShortArray>): ShortArray {
        val out = ShortArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) {
            c.copyInto(out, pos)
            pos += c.size
        }
        return out
    }

    fun durationMs(samples: Int, sampleRate: Int = SAMPLE_RATE): Long = samples * 1000L / sampleRate
}

object Wav {
    /** Wraps 16-bit mono PCM in a canonical 44-byte RIFF/WAVE header. */
    fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
        val data = Pcm.toBytes(samples)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + data.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // PCM
            putShort(1) // mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // byte rate
            putShort(2) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(data.size)
        }
        return header.array() + data
    }
}

/**
 * Incrementally strips a WAV header from a byte stream. Feed network chunks to [push]; once the
 * `data` chunk has been located, [sampleRate] is known and the PCM payload is returned.
 * Streaming servers often write 0 / 0xFFFFFFFF as chunk sizes, so sizes are not trusted.
 */
class WavStreamParser {
    private var header = ByteArray(0)
    var sampleRate: Int? = null
        private set
    var channels: Int = 1
        private set
    var bitsPerSample: Int = 16
        private set
    private var headerDone = false

    /** Returns PCM bytes contained in [chunk] (possibly empty while the header is incomplete). */
    fun push(chunk: ByteArray): ByteArray {
        if (headerDone) return chunk
        header += chunk
        val parsed = tryParse() ?: run {
            if (header.size > 64 * 1024) throw IllegalStateException("Invalid WAV stream: no data chunk")
            return ByteArray(0)
        }
        headerDone = true
        val rest = header.copyOfRange(parsed, header.size)
        header = ByteArray(0)
        return rest
    }

    private fun tryParse(): Int? {
        val h = header
        if (h.size < 12) return null
        if (String(h, 0, 4, Charsets.US_ASCII) != "RIFF" || String(h, 8, 4, Charsets.US_ASCII) != "WAVE") {
            // Not a WAV at all: treat as raw PCM at an unknown rate (caller falls back to 24 kHz).
            return 0
        }
        var pos = 12
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        while (pos + 8 <= h.size) {
            val id = String(h, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > h.size) return null
                    channels = bb.getShort(body + 2).toInt()
                    sampleRate = bb.getInt(body + 4)
                    bitsPerSample = bb.getShort(body + 14).toInt()
                }
                "data" -> return body
            }
            if (size < 0 || size > 1 shl 20) return null // only small chunks precede "data"
            pos = body + size + (size and 1)
        }
        return null
    }
}
