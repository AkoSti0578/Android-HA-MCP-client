package nl.ako.hachat.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Minimale JSON -> YAML-weergave (block style), bedoeld om automation-configs leesbaar
 * te tonen in de bevestigingskaart. Strings worden alleen gequote als dat nodig is.
 */
object YamlWriter {

    fun write(element: JsonElement): String = buildString { emit(element, 0, this, inSeq = false) }.trimEnd() + "\n"

    private fun emit(e: JsonElement, indent: Int, sb: StringBuilder, inSeq: Boolean) {
        when (e) {
            is JsonObject -> {
                if (e.isEmpty()) { sb.append("{}\n"); return }
                var first = true
                for ((k, v) in e) {
                    if (!(inSeq && first)) sb.append(" ".repeat(indent))
                    first = false
                    sb.append(key(k)).append(":")
                    writeValueAfterKey(v, indent, sb)
                }
            }
            is JsonArray -> {
                if (e.isEmpty()) { sb.append("[]\n"); return }
                var first = true
                for (item in e) {
                    if (!(inSeq && first)) sb.append(" ".repeat(indent))
                    first = false
                    sb.append("- ")
                    when (item) {
                        is JsonObject -> if (item.isEmpty()) sb.append("{}\n") else emit(item, indent + 2, sb, inSeq = true)
                        is JsonArray -> if (item.isEmpty()) sb.append("[]\n") else emit(item, indent + 2, sb, inSeq = true)
                        else -> sb.append(scalar(item, indent + 2)).append("\n")
                    }
                }
            }
            else -> sb.append(scalar(e, indent)).append("\n")
        }
    }

    private fun writeValueAfterKey(v: JsonElement, indent: Int, sb: StringBuilder) {
        when {
            v is JsonObject && v.isNotEmpty() -> { sb.append("\n"); emit(v, indent + 2, sb, inSeq = false) }
            v is JsonArray && v.isNotEmpty() -> { sb.append("\n"); emit(v, indent + 2, sb, inSeq = false) }
            v is JsonObject -> sb.append(" {}\n")
            v is JsonArray -> sb.append(" []\n")
            else -> sb.append(" ").append(scalar(v, indent + 2)).append("\n")
        }
    }

    private fun key(k: String): String = if (needsQuote(k)) quote(k) else k

    private fun scalar(e: JsonElement, indent: Int): String = when {
        e is JsonNull -> "null"
        e is JsonPrimitive && !e.isString -> e.content
        e is JsonPrimitive -> {
            val s = e.content
            when {
                s.contains('\n') -> "|-\n" + s.lines().joinToString("\n") { " ".repeat(indent) + it }
                needsQuote(s) -> quote(s)
                else -> s
            }
        }
        else -> e.toString()
    }

    private val numberLike = Regex("^[-+]?(0x[0-9a-fA-F]+|[0-9][0-9_:.\\-]*)$")

    private val reserved = setOf("true", "false", "yes", "no", "on", "off", "null", "~", "y", "n")

    private fun needsQuote(s: String): Boolean =
        s.isEmpty() ||
            s.lowercase() in reserved ||
            s.toDoubleOrNull() != null ||
            numberLike.matches(s) || // 23:00:00 (sexagesimaal in YAML 1.1), 2024-01-01 (datum), 0x1F
            s.contains('\t') ||
            s.first().isWhitespace() || s.last().isWhitespace() ||
            s.first() in "-?:,[]{}#&*!|>'\"%@`" ||
            s.contains(": ") || s.contains(" #") || s.endsWith(":")

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
