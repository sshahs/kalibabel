package email.sshah.kalibabel.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    private fun parse(vararg lines: String): List<SseEvent> {
        val p = SseParser()
        val out = lines.mapNotNull { p.feed(it) }.toMutableList()
        p.finish()?.let { out += it }
        return out
    }

    @Test
    fun parsesEventsAndData() {
        val events = parse("event: content_block_delta", "data: {\"a\":1}", "", "data: [DONE]", "")
        assertEquals(2, events.size)
        assertEquals("content_block_delta", events[0].event)
        assertEquals("{\"a\":1}", events[0].data)
        assertNull(events[1].event)
        assertEquals("[DONE]", events[1].data)
    }

    @Test
    fun joinsMultiLineDataAndIgnoresComments() {
        val events = parse(": keep-alive", "data: one", "data:two", "")
        assertEquals(listOf(SseEvent(null, "one\ntwo")), events)
    }

    @Test
    fun flushesTrailingEventWithoutBlankLine() {
        assertEquals(listOf(SseEvent(null, "tail")), parse("data: tail"))
    }
}
