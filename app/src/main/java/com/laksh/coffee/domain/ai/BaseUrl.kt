package com.laksh.coffee.domain.ai

import com.laksh.coffee.domain.ai.AiError.InvalidBaseUrl.Reason
import java.net.URI
import java.util.Locale

sealed interface BaseUrlCheck {
    data class Valid(val normalized: String, val host: String) : BaseUrlCheck
    data class Invalid(val error: AiError.InvalidBaseUrl) : BaseUrlCheck
}

/**
 * Validates and normalises the user-editable API root. Only a [BaseUrlCheck.Valid] result is
 * ever stored, and every request URL is built from it, so these rules are the one gate between
 * the key and the network.
 */
object BaseUrl {

    private val PASTED_SUFFIXES = listOf("/chat/completions", "/messages", "/models")

    // Same shapes Redact scrubs, but anchored to a word start so hosts like "disk-cache" pass.
    private val KEY_TOKEN = Regex("(?<![A-Za-z0-9])(sk|cc|key)[-_][A-Za-z0-9_-]{8,}")

    fun check(raw: String): BaseUrlCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return invalid(Reason.Blank)
        // A bare key has no dot and no slash. Catch it before it is ever treated as a host name,
        // because resolving it would hand the key to the DNS resolver.
        if (looksLikeBareKey(trimmed)) return invalid(Reason.LooksLikeKey)

        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return invalid(Reason.Malformed)
        if (!uri.scheme.equals("https", ignoreCase = true)) return invalid(Reason.NotHttps)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return invalid(Reason.Malformed)
        if (uri.rawUserInfo != null) return invalid(Reason.HasCredentials)
        if (uri.rawQuery != null || uri.rawFragment != null) return invalid(Reason.HasQuery)
        val rawPath = uri.rawPath.orEmpty()
        if (hostLooksLikeKey(host) || KEY_TOKEN.containsMatchIn(rawPath) || tailLooksLikeKey(rawPath)) {
            return invalid(Reason.LooksLikeKey)
        }
        // Every real API host has a dot; a dotless one is a typo or a pasted token.
        if ('.' !in host) return invalid(Reason.Malformed)

        val lowerHost = host.lowercase(Locale.ROOT)
        val port = if (uri.port != -1) ":${uri.port}" else ""
        var path = uri.rawPath.orEmpty().trimEnd('/')
        PASTED_SUFFIXES.firstOrNull { path.endsWith(it, ignoreCase = true) }?.let { suffix ->
            path = path.dropLast(suffix.length)
        }
        path = path.trimEnd('/')
        return BaseUrlCheck.Valid(normalized = "https://$lowerHost$port$path", host = lowerHost)
    }

    fun chatCompletions(normalized: String): String = "$normalized/chat/completions"

    /** Anthropic's Messages endpoint. */
    fun messages(normalized: String): String = "$normalized/messages"

    /** [path] is the catalog's listModels.path; anything that isn't a plain path falls back to /models. */
    fun models(normalized: String, path: String? = null): String =
        normalized + (path?.takeIf { it.startsWith("/") && "?" !in it && "#" !in it && ".." !in it } ?: "/models")

    fun hostOf(normalized: String): String =
        runCatching { URI(normalized).host }.getOrNull()?.lowercase(Locale.ROOT) ?: ""

    private fun invalid(reason: Reason): BaseUrlCheck = BaseUrlCheck.Invalid(AiError.InvalidBaseUrl(reason))

    private fun looksLikeBareKey(text: String): Boolean =
        '.' !in text && '/' !in text && (text.length >= 16 || KEY_TOKEN.containsMatchIn(text))

    /**
     * An unprefixed key stuck on the end of a real address: the last path segment, after any
     * pasted endpoint suffix, looks like a random token. Only the tail is checked, because
     * gateways carry long ids mid-path (a Cloudflare account id is followed by the gateway
     * name), and those are real addresses.
     */
    private fun tailLooksLikeKey(rawPath: String): Boolean {
        var path = rawPath.trimEnd('/')
        PASTED_SUFFIXES.firstOrNull { path.endsWith(it, ignoreCase = true) }?.let { suffix ->
            path = path.dropLast(suffix.length).trimEnd('/')
        }
        val tail = path.substringAfterLast('/')
        val noVowels = tail.length >= 20 && '-' !in tail && tail.none { it.lowercaseChar() in "aeiou" }
        return looksRandom(tail) || noVowels
    }

    private fun hostLooksLikeKey(host: String): Boolean =
        host.split('.').any { label ->
            (KEY_TOKEN.containsMatchIn(label) && label.any { it.isDigit() }) || looksRandom(label)
        }

    /**
     * A DNS label of 20+ characters that keeps switching between upper case, lower case and
     * digits is a token, not a name. Real long labels are words joined by hyphens.
     */
    private fun looksRandom(label: String): Boolean {
        val chars = label.filter { it != '-' }
        if (chars.length < 20 || chars.none { it.isDigit() }) return false
        var switches = 0
        for (i in 1 until chars.length) {
            if (charClass(chars[i]) != charClass(chars[i - 1])) switches++
        }
        return switches >= 6
    }

    private fun charClass(c: Char): Int = when {
        c.isDigit() -> 0
        c.isUpperCase() -> 1
        else -> 2
    }
}
