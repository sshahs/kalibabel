package email.sshah.kalibabel.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavTest {
    @Test
    fun encodedWavRoundTripsThroughStreamingParser() {
        val samples = ShortArray(1000) { (it * 31).toShort() }
        val wav = Wav.encode(samples, 24_000)
        assertEquals(44 + 2000, wav.size)

        val parser = WavStreamParser()
        val out = java.io.ByteArrayOutputStream()
        wav.toList().chunked(7).forEach { out.write(parser.push(it.toByteArray())) }
        assertEquals(24_000, parser.sampleRate)
        assertArrayEquals(Pcm.toBytes(samples), out.toByteArray())
    }

    @Test
    fun rawPcmPassesThroughWithUnknownRate() {
        val parser = WavStreamParser()
        val bytes = ByteArray(64) { it.toByte() }
        val out = parser.push(bytes)
        assertEquals(null, parser.sampleRate)
        assertArrayEquals(bytes, out)
    }
}
