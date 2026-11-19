package com.laksh.coffee.data.ai

import com.laksh.coffee.domain.ai.AiError
import com.laksh.coffee.domain.ai.AiJson
import com.laksh.coffee.domain.ai.AiProviders
import com.laksh.coffee.domain.ai.AiResult
import com.laksh.coffee.domain.ai.AuthStyle
import com.laksh.coffee.domain.ai.BaseUrl
import com.laksh.coffee.domain.ai.BaseUrlCheck
import com.laksh.coffee.domain.ai.Redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * One request. Not a data class on purpose: [headers] carries the key, and a generated
 * toString would print it.
 */
class HttpRequest(
    val url: String,
    val host: String,
    /** The auth header and the provider's extra headers. Accept, User-Agent and Content-Type are the transport's. */
    val headers: Map<String, String>,
    /** JSON to POST, or null for a GET. */
    val body: String?,
    val readTimeoutMs: Int,
)

class HttpReply(
    val code: Int,
    val contentType: String?,
    val body: String,
    val location: String? = null,
    val retryAfter: String? = null,
    val latencyMs: Long = 0,
) {
    val isHtml: Boolean get() = contentType?.contains("html", ignoreCase = true) == true
    val ok: Boolean get() = code in 200..299
}

/** The network seam: the clients build requests and read replies, a test can stand in for the wire. */
fun interface Transport {
    suspend fun exchange(request: HttpRequest): AiResult<HttpReply>
}

/**
 * The app's only HTTP code, on the platform's HttpsURLConnection so no HTTP library is needed.
 * Every failure comes back as an [AiError]; nothing is ever logged.
 *
 * - https only, and redirects are never followed, so a key cannot be forwarded elsewhere.
 * - The key travels only in a request header: never in a URL, a log or an error text.
 * - Cancelling the calling coroutine closes the socket, so Cancel works mid-request.
 * - A reply is read up to 1 MiB and no further.
 */
class HttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val userAgent: String = "Coffee-Android",
) : Transport {

    // Where the request had got to, so a timeout can say whether anything was sent.
    private class Progress {
        @Volatile var stage: Int = CONNECTING
    }

    /** One HTTP round trip on the IO pool, with a hard deadline and a working cancel. */
    override suspend fun exchange(request: HttpRequest): AiResult<HttpReply> {
        val readTimeoutMs = request.readTimeoutMs
        val host = request.host
        // readTimeout only bounds each read, so a server that trickles bytes could hold the
        // request open forever. This is the total deadline.
        val outcome: AiResult<HttpReply>? = withTimeoutOrNull(readTimeoutMs + 5_000L) {
            withContext(Dispatchers.IO) {
                val conn = URI(request.url).toURL().openConnection() as? HttpsURLConnection
                    ?: return@withContext AiResult.Err(AiError.InvalidBaseUrl(AiError.InvalidBaseUrl.Reason.NotHttps))
                val progress = Progress()
                coroutineScope {
                    // A blocking socket read ignores coroutine cancellation. Closing the
                    // connection is what makes Cancel, or the deadline, take effect.
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            conn.disconnect()
                        }
                    }
                    try {
                        AiResult.Ok(send(conn, request, progress))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AiResult.Err(
                            mapException(e = e, host = host, stage = progress.stage, readTimeoutMs = readTimeoutMs),
                        )
                    } finally {
                        watcher.cancel()
                    }
                }
            }
        }
        return outcome ?: AiResult.Err(AiError.ReadTimeout(host, readTimeoutMs / 1000))
    }

    private fun send(conn: HttpsURLConnection, request: HttpRequest, progress: Progress): HttpReply {
        val startedNs = System.nanoTime()
        val body = request.body
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = request.readTimeoutMs
        conn.requestMethod = if (body != null) "POST" else "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", userAgent)
        request.headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        val bytes = body?.toByteArray(Charsets.UTF_8)
        if (bytes != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(bytes.size)
        }

        conn.connect()
        progress.stage = WRITING
        if (bytes != null) conn.outputStream.use { it.write(bytes) }
        progress.stage = READING

        val code = conn.responseCode
        val contentType = conn.contentType
        val location = conn.getHeaderField("Location")
        val retryAfter = conn.getHeaderField("Retry-After")
        val text = if (code in 300..399) {
            ""
        } else {
            val stream = try {
                if (code >= 400) conn.errorStream else conn.inputStream
            } catch (e: IOException) {
                conn.errorStream
            }
            readCapped(stream)
        }
        val latencyMs = (System.nanoTime() - startedNs) / 1_000_000
        return HttpReply(
            code = code,
            contentType = contentType,
            body = text,
            location = location,
            retryAfter = retryAfter,
            latencyMs = latencyMs,
        )
    }

    /** Reads at most 1 MiB, so a runaway reply can't exhaust memory. */
    private fun readCapped(stream: InputStream?): String {
        if (stream == null) return ""
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (total < MAX_BODY_BYTES) {
                val n = input.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES - total))
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun mapException(e: Exception, host: String, stage: Int, readTimeoutMs: Int): AiError = when {
        e is SocketTimeoutException ->
            if (stage == READING) AiError.ReadTimeout(host, readTimeoutMs / 1000) else AiError.ConnectTimeout(host)
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ->
            AiError.Offline(host)
        e is SSLException || e.causedBy(CertificateException::class.java) ->
            AiError.Tls(host)
        e is SocketException && stage == CONNECTING ->
            AiError.Offline(host)
        // The type name only: an exception's message can quote a URL or a header.
        else -> AiError.Unknown(e.javaClass.simpleName.ifBlank { "Exception" })
    }

    private fun Throwable.causedBy(type: Class<out Throwable>): Boolean =
        generateSequence(this) { it.cause }.take(10).any { type.isInstance(it) }

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024

        const val CONNECTING = 0
        const val WRITING = 1
        const val READING = 2
    }
}

/** What both wire formats share: checking the address, the request headers, and reading failures. */
internal object Http {
    const val MODELS_READ_TIMEOUT_MS = 20_000
    const val TEST_READ_TIMEOUT_MS = 45_000

    /** Checks the address before anything touches the network, DNS included. */
    fun checkBase(baseUrl: String, apiKey: String?): AiResult<BaseUrlCheck.Valid> =
        when (val c = BaseUrl.check(baseUrl)) {
            is BaseUrlCheck.Invalid -> AiResult.Err(c.error)
            is BaseUrlCheck.Valid -> when {
                // A dotless host is never a real API; it is most often a pasted key, and
                // resolving it would hand the key to the DNS server.
                '.' !in c.host ->
                    AiResult.Err(AiError.InvalidBaseUrl(AiError.InvalidBaseUrl.Reason.Malformed))
                apiKey != null && apiKey.length >= 8 && c.normalized.contains(apiKey) ->
                    AiResult.Err(AiError.InvalidBaseUrl(AiError.InvalidBaseUrl.Reason.HasCredentials))
                else -> AiResult.Ok(c)
            }
        }

    /**
     * The key in the one header this provider reads it from, then the catalog's extra headers.
     * Extra headers pass the allow-list again here, so nothing can replace or echo the key.
     */
    fun headers(e: Endpoint, apiKey: String?, defaults: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        defaults.forEach { (k, v) -> put(k, v) }
        e.extraHeaders.forEach { (k, v) ->
            if (AiProviders.headerAllowed(k, v)) {
                // Header names are case-insensitive: a catalog value replaces a default of the same name.
                keys.firstOrNull { it.equals(k, ignoreCase = true) }?.let { remove(it) }
                put(k, v)
            }
        }
        if (!apiKey.isNullOrBlank()) {
            when (e.auth) {
                AuthStyle.Bearer -> put("Authorization", "Bearer $apiKey")
                AuthStyle.XApiKey -> put("x-api-key", apiKey)
            }
        }
    }

    fun statusError(reply: HttpReply, host: String, path: String, model: String?, apiKey: String?): AiError {
        val code = reply.code
        val message = Redact.secrets(providerText(reply.body), apiKey)
        return when {
            code in 300..399 -> AiError.Redirected(code, reply.location?.let { locationHost(it) })
            code == 401 || code == 403 -> AiError.Unauthorized(
                code = code,
                host = host,
                model = model.orEmpty(),
                providerMessage = message,
            )
            code == 402 -> AiError.PaymentRequired(host, message)
            code == 404 && model != null && reply.body.contains(model, ignoreCase = true) ->
                AiError.ModelNotFound(host, model)
            (code == 400 || code == 422) && model != null && saysModelMissing(message, model) ->
                AiError.ModelNotFound(host, model)
            code == 404 -> AiError.EndpointNotFound(host, path)
            code == 429 -> AiError.RateLimited(host, reply.retryAfter?.trim()?.toIntOrNull())
            code == 400 || code == 413 || code == 422 -> AiError.BadRequest(code, host, message)
            // 529 is Anthropic's "overloaded"; like any 5xx it is on their side.
            code == 408 || code >= 500 -> AiError.ServerError(code, host)
            else -> AiError.BadRequest(code, host, message)
        }
    }

    private fun saysModelMissing(message: String?, model: String): Boolean {
        if (message.isNullOrBlank()) return false
        val namesModel = message.contains(model, ignoreCase = true) || MODEL_WORD.containsMatchIn(message)
        // "invalid temperature for this model" is a parameter problem, not a missing model.
        val aboutParameter = PARAMETER_WORDS.containsMatchIn(message)
        return namesModel && !aboutParameter && MISSING_WORDS.containsMatchIn(message)
    }

    /** The provider's own error text, or a short plain-text body. Never an HTML page. */
    fun providerText(body: String): String? {
        if (body.isBlank()) return null
        val element: JsonElement? = runCatching { AiJson.wire.parseToJsonElement(body) }.getOrNull()
        if (element != null) return element.errorMessage()
        val trimmed = body.trim()
        return trimmed.takeUnless { it.startsWith("<") }
    }

    fun pathOf(url: String): String = runCatching { URI(url).path }.getOrNull().orEmpty()

    private fun locationHost(location: String): String? =
        runCatching { URI(location).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private val MODEL_WORD = Regex("""\bmodel\b""", RegexOption.IGNORE_CASE)
    private val MISSING_WORDS = Regex("not found|does not exist|doesn't exist|invalid|unknown|not supported", RegexOption.IGNORE_CASE)
    private val PARAMETER_WORDS = Regex("temperature|max_tokens|max_completion_tokens|top_p|parameter", RegexOption.IGNORE_CASE)
}
