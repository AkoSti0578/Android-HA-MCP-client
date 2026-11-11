package nl.ako.hachat.data.anthropic

import java.io.IOException

/** Fouten van de Claude API, met een Nederlandse, mensvriendelijke boodschap. */
sealed class ClaudeApiException(message: String, val retryable: Boolean) : IOException(message) {
    class MissingKey : ClaudeApiException("Geen Claude API key ingesteld. Ga naar Instellingen.", false)
    class Unauthorized : ClaudeApiException("Ongeldige API key (401). Controleer je key in Instellingen.", false)
    class Forbidden(detail: String) : ClaudeApiException("Geen toegang (403): $detail", false)
    class NotFound(detail: String) : ClaudeApiException("Niet gevonden (404): $detail — bestaat het gekozen model nog? Ververs de modellijst.", false)
    class RateLimited(val retryAfterSeconds: Long?) : ClaudeApiException(
        "Rate limit bereikt (429)." + (retryAfterSeconds?.let { " Probeer het over ${it}s opnieuw." } ?: " Probeer het zo opnieuw."),
        true,
    )
    class Overloaded : ClaudeApiException("Claude is tijdelijk overbelast (529). Probeer het zo opnieuw.", true)
    class Server(code: Int, detail: String) : ClaudeApiException("Serverfout bij Anthropic ($code): $detail", true)
    class BadRequest(detail: String) : ClaudeApiException("Ongeldig verzoek (400): $detail", false)
    class TooLarge : ClaudeApiException("Verzoek te groot (413). Begin een nieuw gesprek of verklein de toollijst.", false)
    class StreamError(type: String, detail: String) : ClaudeApiException(
        "Fout tijdens streamen ($type): $detail", type == "overloaded_error" || type == "api_error",
    )
    class Network(cause: Throwable) : ClaudeApiException(
        "Kan api.anthropic.com niet bereiken: ${cause.message ?: cause.javaClass.simpleName}. Heb je internet?", true,
    )

    companion object {
        fun fromHttp(code: Int, errorMessage: String, retryAfter: Long?): ClaudeApiException = when (code) {
            400 -> BadRequest(errorMessage)
            401 -> Unauthorized()
            403 -> Forbidden(errorMessage)
            404 -> NotFound(errorMessage)
            413 -> TooLarge()
            429 -> RateLimited(retryAfter)
            529 -> Overloaded()
            else -> Server(code, errorMessage)
        }
    }
}
