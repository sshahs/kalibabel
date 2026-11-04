package email.sshah.kalibabel.net

/** A server-sent event. */
data class SseEvent(val event: String?, val data: String)

/**
 * Incremental parser for the `text/event-stream` format. Feed it one line at a time (without the
 * line terminator); it returns an event whenever a blank line completes one.
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    /** Call at end of stream to emit a trailing event that was not followed by a blank line. */
    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        if (!hasData && event == null) return null
        val ev = SseEvent(event, data.toString())
        event = null
        data.setLength(0)
        hasData = false
        return ev
    }
}
