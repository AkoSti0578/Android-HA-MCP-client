package nl.ako.hachat.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Houdt geheimen uit foutmeldingen en UI-teksten. */
object Redaction {
    /** "http://192.168.1.5:9583/private_abc123" -> "http://192.168.1.5:9583/…" */
    fun url(raw: String): String {
        val u = raw.trim().toHttpUrlOrNull() ?: return "(ongeldige URL)"
        val path = if (u.encodedPath.length > 1) "/…" else ""
        return "${u.scheme}://${u.host}:${u.port}$path"
    }

    /** Vervangt elke bekende geheime waarde in een tekst. */
    fun scrub(text: String, vararg secrets: String?): String {
        var out = text
        secrets.filterNotNull().filter { it.length >= 6 }.forEach { s ->
            out = out.replace(s, "•••")
            // ook het geheime pad-segment van een MCP-URL
            s.toHttpUrlOrNull()?.encodedPath?.takeIf { it.length > 6 }?.let { out = out.replace(it, "/…") }
        }
        return out
    }
}
