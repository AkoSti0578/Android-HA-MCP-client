package nl.ako.hachat.core

import okio.BufferedSource

/** Eén Server-Sent Event. */
data class SseEvent(val event: String?, val data: String, val id: String? = null)

/**
 * Minimale, spec-conforme SSE-parser (WHATWG): `event:`, `data:` (meerdere regels samengevoegd),
 * `id:`, commentaarregels (`:`) en lege regel = dispatch. Werkt voor zowel de Claude API als MCP.
 */
class SseReader(private val source: BufferedSource) {
    private var event: String? = null
    private var id: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** Volgende event, of null bij einde stream. Blokkerend — aanroepen op Dispatchers.IO. */
    fun next(): SseEvent? {
        while (true) {
            val line = source.readUtf8Line() ?: return flush()
            if (line.isEmpty()) {
                flush()?.let { return it }
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon == -1) line else line.substring(0, colon)
            var value = if (colon == -1) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value); hasData = true
                }
                "id" -> id = value
            }
        }
    }

    private fun flush(): SseEvent? {
        val result = if (hasData) SseEvent(event, data.toString(), id) else null
        event = null; data.setLength(0); hasData = false
        return result
    }

    fun asSequence(): Sequence<SseEvent> = generateSequence { next() }
}
