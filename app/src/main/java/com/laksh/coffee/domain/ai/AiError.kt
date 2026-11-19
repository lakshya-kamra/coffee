package com.laksh.coffee.domain.ai

/** Outcome of any AI call. Failures carry an [AiError] whose text is ready to show. */
sealed interface AiResult<out T> {
    data class Ok<out T>(val value: T) : AiResult<T>
    data class Err(val error: AiError) : AiResult<Nothing>
}

/**
 * Every way the AI feature can fail, with the exact words the user sees.
 *
 * Messages are built only from the host, the model id and a provider message that has already
 * been through [Redact.secrets]. Exception text never reaches them, so a key cannot leak into
 * the UI through an error.
 */
sealed class AiError {
    abstract val title: String
    abstract val message: String
    open val retryable: Boolean = true
    open val opensSettings: Boolean = false
    open val maybeBilled: Boolean = false

    // ------------------------------------------------------------ setup and local errors

    /**
     * [host] is where requests would go and [keyHost] where the saved key belongs ("" when none
     * is saved), so the message can say which host needs a key. An empty [host] means no
     * provider has been chosen yet (a fresh install).
     */
    data class NotConfigured(
        val missingKey: Boolean,
        val missingModel: Boolean,
        val host: String = "",
        val keyHost: String = "",
    ) : AiError() {
        override val title: String get() = "Analysis isn't set up"
        override val message: String
            get() = when {
                keyHost.isNotEmpty() && host.isNotEmpty() && keyHost != host ->
                    "Your key is saved for $keyHost. Paste a key for $host in Gear › App › AI analysis."
                host.isEmpty() ->
                    "Choose a provider and add your own API key in Gear › App › AI analysis. " +
                        "Nothing is sent until you do."
                missingModel && !missingKey -> "Pick a model in Gear › App › AI analysis."
                else -> "Add your API key for ${providerName(host)} in Gear › App › AI analysis."
            }
        override val opensSettings: Boolean get() = true
    }

    data class InvalidBaseUrl(val reason: Reason) : AiError() {
        enum class Reason { Blank, NotHttps, Malformed, HasCredentials, HasQuery, LooksLikeKey }

        override val title: String
            get() = when (reason) {
                Reason.Blank -> "Base URL is empty"
                Reason.NotHttps -> "Base URL must use https"
                Reason.Malformed -> "That isn't a web address"
                Reason.HasCredentials -> "Take the key out of the address"
                Reason.HasQuery -> "Remove the ?… part"
                Reason.LooksLikeKey -> "That looks like your API key"
            }

        override val message: String
            get() = when (reason) {
                Reason.Blank -> "Enter the API address, for example $EXAMPLE_URL."
                Reason.NotHttps -> "Plain http would send your key and your brews unencrypted, so the app " +
                    "won't use it. Start the address with https://."
                Reason.Malformed -> "It should look like $EXAMPLE_URL."
                Reason.HasCredentials -> "Paste the key into the API key field. It never belongs in a URL."
                Reason.HasQuery -> "The base URL is just the API root, with no query string or #fragment."
                Reason.LooksLikeKey -> "Paste it into the API key field. The base URL is the API address, " +
                    "for example $EXAMPLE_URL."
            }

        // The address itself is wrong, so the only fix is in settings.
        override val retryable: Boolean get() = false
        override val opensSettings: Boolean get() = true
    }

    data object KeyUnreadable : AiError() {
        override val title: String get() = "Saved key can't be unlocked"
        override val message: String
            get() = "This phone's key store no longer has the key that protected it. That happens after " +
                "a restore or a security reset. Paste the key again."
        override val opensSettings: Boolean get() = true
    }

    data class NotEnoughData(val needed: Int, val have: Int) : AiError() {
        override val title: String get() = "Not enough brews yet"
        override val message: String get() = "Patterns need at least $needed rated brews. You have $have."
        override val retryable: Boolean get() = false
    }

    data object BrewMissing : AiError() {
        override val title: String get() = "This brew no longer exists"
        override val message: String get() = "It was deleted while the analysis was running. Nothing was saved."
        override val retryable: Boolean get() = false
    }

    // ------------------------------------------------------------------ network errors

    data class Offline(val host: String) : AiError() {
        override val title: String get() = "Can't reach $host"
        override val message: String get() = "Check you're online. Nothing was sent and nothing was charged."
    }

    data class ConnectTimeout(val host: String) : AiError() {
        override val title: String get() = "No answer from $host"
        override val message: String
            get() = "The server didn't pick up within 15 seconds. Nothing was sent. Try again in a moment."
    }

    data class ReadTimeout(val host: String, val seconds: Int) : AiError() {
        override val title: String get() = "Still thinking after $seconds seconds"
        override val message: String
            get() = "The model didn't finish in time, so the app stopped waiting. Reasoning models are " +
                "sometimes slow. Try again, or pick a faster model. The provider may still bill the " +
                "tokens it used."
        override val maybeBilled: Boolean get() = true
    }

    data class Tls(val host: String) : AiError() {
        override val title: String get() = "Couldn't open a secure connection to $host"
        override val message: String
            get() = "The certificate check failed. On hotel or work Wi-Fi that inspects traffic, try mobile data."
    }

    data class Redirected(val code: Int, val locationHost: String?) : AiError() {
        override val title: String get() = "The address redirects (HTTP $code)"
        override val message: String
            get() = "The server tried to send the app to ${locationHost ?: "another address"}. Put the final " +
                "address in the base URL instead. The app won't forward your key somewhere else."
        override val opensSettings: Boolean get() = true
    }

    // -------------------------------------------------------------- HTTP status errors

    data class Unauthorized(
        val code: Int,
        val host: String,
        val model: String,
        val providerMessage: String?,
    ) : AiError() {
        private val isCommandCode: Boolean get() = host == ModelCatalog.COMMANDCODE_HOST

        override val title: String
            get() = if (isCommandCode) {
                "CommandCode didn't accept the key (HTTP $code)"
            } else {
                "${providerName(host)} didn't accept the key (HTTP $code)"
            }

        override val message: String
            get() = if (isCommandCode) {
                "CommandCode only opens its Provider API, the endpoint this app calls, to the separate " +
                    "Provider plan (\$15 a month). The Go, GOAT, Pro and Max coding plans don't include it, " +
                    "even with a valid key. If that's your plan, add Provider at commandcode.ai, or choose " +
                    "another provider in Gear › App › AI analysis. If you are on Provider, check the " +
                    "whole key was pasted." +
                    (providerMessage?.let { "\n\nCommandCode said: \"$it\"" } ?: "")
            } else {
                "Check the key is complete, belongs to ${providerName(host)} and is allowed to use $model." +
                    (providerMessage?.let { " Server said: \"$it\"" } ?: "")
            }

        override val opensSettings: Boolean get() = true
    }

    data class PaymentRequired(val host: String, val providerMessage: String?) : AiError() {
        override val title: String get() = "Out of credit (HTTP 402)"
        override val message: String
            get() = "$host says the account has no credit left. Top up there, then try again." +
                if (host == ModelCatalog.COMMANDCODE_HOST) {
                    " If you're not on the Provider plan, that's the likely cause: the Provider API is " +
                        "only included in the \$15/month Provider plan."
                } else {
                    ""
                }
    }

    data class ModelNotFound(val host: String, val model: String) : AiError() {
        override val title: String get() = "Model not available"
        override val message: String
            get() = "\"$model\" isn't offered at $host. Pick another model in Gear › App › AI analysis."
        override val opensSettings: Boolean get() = true
    }

    data class EndpointNotFound(val host: String, val path: String) : AiError() {
        override val title: String get() = "Nothing at that address (HTTP 404)"
        override val message: String
            get() = "$host$path doesn't answer chat requests. For a custom address, the base URL should " +
                "be the API root, usually ending in /v1."
        override val opensSettings: Boolean get() = true
    }

    data class RateLimited(val host: String, val retryAfterSec: Int?) : AiError() {
        override val title: String get() = "Too many requests (HTTP 429)"
        override val message: String
            get() = when {
                retryAfterSec == null -> "Wait a minute and try again."
                retryAfterSec == 1 -> "Wait 1 second and try again."
                else -> "Wait $retryAfterSec seconds and try again."
            }
    }

    data class BadRequest(val code: Int, val host: String, val providerMessage: String?) : AiError() {
        override val title: String get() = "Request rejected (HTTP $code)"
        override val message: String
            get() = "$host didn't accept the request: ${providerMessage ?: "no reason given"}. " +
                "Try again, or pick another model."
    }

    data class ServerError(val code: Int, val host: String) : AiError() {
        override val title: String get() = "$host had a problem (HTTP $code)"
        override val message: String get() = "That's on their side. Nothing was saved. Try again in a minute."
    }

    // ------------------------------------------------------------------- reply errors

    data class Truncated(val completionTokens: Int?, val costLabel: String?) : AiError() {
        override val title: String get() = "The answer was cut off"
        override val message: String
            get() = "The model used its whole length budget " +
                "(${completionTokens?.let { ModelCatalog.formatTokens(it) } ?: "all its"} tokens) before it " +
                "finished, so there's nothing reliable to show. Try again, or pick another model. " +
                "Those tokens are billed" + (costLabel?.let { " ($it)" } ?: "") + "."
        override val maybeBilled: Boolean get() = true
    }

    data object EmptyReply : AiError() {
        override val title: String get() = "The model returned nothing"
        override val message: String
            get() = "It answered with an empty message. Try again, or pick another model."
        override val maybeBilled: Boolean get() = true
    }

    data class Filtered(val host: String) : AiError() {
        override val title: String get() = "The provider blocked the reply"
        override val message: String get() = "$host's content filter stopped the answer. Try again, or switch model."
    }

    data class NotOpenAiFormat(val host: String) : AiError() {
        override val title: String get() = "Unexpected reply from $host"
        override val message: String
            get() = "It answered, but not in the format the app expects. For a custom address, check the " +
                "base URL: it should be the API root, usually ending in /v1, not a website."
        override val opensSettings: Boolean get() = true
    }

    /** [kind] is the exception's simple class name only, never its message. */
    data class Unknown(val kind: String) : AiError() {
        override val title: String get() = "Something went wrong"
        override val message: String get() = "The request failed ($kind). Nothing was saved. Try again."
    }
}

private const val EXAMPLE_URL = "https://api.example.com/v1"

/** The preset's name when [host] is one of the catalog's, else the host itself. */
private fun providerName(host: String): String = AiProviders.forHost(host)?.name ?: host

/** Scrubs anything key-shaped out of text that came back from a provider. */
object Redact {

    private const val MASK = "•••"
    private val BEARER = Regex("(?i)bearer\\s+[^\\s\"',;]+")
    // The prefixes the catalog's providers document (sk-, sk-ant-, sk-or-, gsk_, xai-, pplx-, fw_, AIza…).
    private val TOKEN = Regex("((sk|cc|key|gsk|xai|pplx|fw)[-_]|AIza)[A-Za-z0-9_-]{8,}")
    private val SPACE = Regex("\\s+")

    fun secrets(text: String?, key: String?, max: Int = 240): String? {
        if (text == null) return null
        var out: String = text
        // Short strings would mask ordinary words, and no real key is that short.
        if (key != null && key.length >= 8) out = out.replace(key, MASK)
        out = BEARER.replace(out, "Bearer $MASK")
        out = TOKEN.replace(out, MASK)
        out = SPACE.replace(out, " ").trim()
        if (max > 0 && out.length > max) out = out.take(max - 1).trimEnd() + "…"
        return out
    }
}
