package com.kernel.ai.core.memory.nextcloud

import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Credentials returned by Nextcloud Login Flow v2 before discovery and secure persistence. */
internal data class NextcloudLoginFlowCredentials(
    val serverUrl: String,
    val username: String,
    val appPassword: String,
)

/**
 * Client for Nextcloud's documented browser-based Login Flow v2.
 *
 * The login and poll URLs are server-provided, but are still constrained to the requested server's
 * host/port and scheme policy before they are opened or sent a token. Polling never follows a
 * redirect and always uses the exact endpoint returned by the server.
 */
internal class NextcloudLoginFlowClient(
    private val transport: CalDavTransport,
    private val timeoutMillis: Long = LOGIN_FLOW_TIMEOUT_MILLIS,
    private val pollIntervalMillis: Long = LOGIN_FLOW_POLL_INTERVAL_MILLIS,
) {
    init {
        require(timeoutMillis > 0L) { "Login Flow timeout must be positive" }
        require(pollIntervalMillis >= 0L) { "Login Flow poll interval cannot be negative" }
    }

    suspend fun authenticate(
        serverUrl: String,
        allowInsecureHttp: Boolean = false,
        onLoginUrl: suspend (String) -> Unit = {},
    ): NextcloudLoginFlowCredentials {
        val requestedServer = normalizeServer(serverUrl, allowInsecureHttp)
        return try {
            withTimeout(timeoutMillis) {
                authenticateWithinTimeout(requestedServer, allowInsecureHttp, onLoginUrl)
            }
        } catch (error: TimeoutCancellationException) {
            throw NextcloudConnectionException(
                NextcloudFailure.Code.TIMEOUT,
                "Nextcloud browser authentication timed out. Try again or use manual app-password setup.",
            )
        } catch (error: CancellationException) {
            throw error
        }
    }

    private suspend fun authenticateWithinTimeout(
        requestedServer: String,
        allowInsecureHttp: Boolean,
        onLoginUrl: suspend (String) -> Unit,
    ): NextcloudLoginFlowCredentials {
        val startUrl = "$requestedServer/index.php/login/v2"
        val startResponse = execute(
            method = "POST",
            url = startUrl,
            headers = JSON_HEADERS,
            body = "",
        )
        when {
            startResponse.status == 404 || startResponse.status == 405 || startResponse.status == 501 ->
                throw loginFlowUnsupported()
            startResponse.status !in 200..299 -> throw loginFlowFailure(startResponse.status)
        }

        val start = parseStartResponse(startResponse.body)
        val loginUrl = validateServerProvidedUrl(
            raw = start.loginUrl,
            requestedServer = requestedServer,
            allowInsecureHttp = allowInsecureHttp,
            purpose = "login",
        )
        val pollEndpoint = validateServerProvidedUrl(
            raw = start.pollEndpoint,
            requestedServer = requestedServer,
            allowInsecureHttp = allowInsecureHttp,
            purpose = "poll",
        )

        // Open the browser before the first poll. The token is never passed to this callback/UI.
        onLoginUrl(loginUrl)
        while (true) {
            val pollResponse = execute(
                method = "POST",
                url = pollEndpoint,
                headers = FORM_HEADERS,
                body = "token=${URLEncoder.encode(start.pollToken, Charsets.UTF_8.name())}",
            )
            when (pollResponse.status) {
                404 -> {
                    if (pollIntervalMillis > 0L) delay(pollIntervalMillis)
                }
                200 -> return parseAndValidateCredentials(
                    body = pollResponse.body,
                    requestedServer = requestedServer,
                    allowInsecureHttp = allowInsecureHttp,
                )
                401, 403 -> throw NextcloudConnectionException(
                    NextcloudFailure.Code.AUTHENTICATION,
                    "Nextcloud browser authentication was not approved. Try again or use manual app-password setup.",
                )
                else -> throw loginFlowFailure(pollResponse.status)
            }
        }
    }

    private suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
    ): CalDavResponse = try {
        transport.execute(method, url, headers, body)
    } catch (error: CancellationException) {
        throw error
    } catch (error: NextcloudFailure) {
        throw error
    } catch (error: Exception) {
        throw OkHttpCalDavTransport.classifyTransportFailure(error)
    }

    private fun parseStartResponse(body: String): StartResponse {
        val root = runCatching { JsonObjectParser(body).parse() }.getOrNull()
            ?: throw malformedLoginFlowResponse()
        val poll = root.objectValue("poll") ?: throw malformedLoginFlowResponse()
        val pollToken = poll.stringValue("token")?.takeIf { it.isNotBlank() }
            ?: throw malformedLoginFlowResponse()
        val pollEndpoint = poll.stringValue("endpoint")?.takeIf { it.isNotBlank() }
            ?: throw malformedLoginFlowResponse()
        val loginUrl = root.stringValue("login")?.takeIf { it.isNotBlank() }
            ?: throw malformedLoginFlowResponse()
        return StartResponse(loginUrl, pollEndpoint, pollToken)
    }

    private fun parseAndValidateCredentials(
        body: String,
        requestedServer: String,
        allowInsecureHttp: Boolean,
    ): NextcloudLoginFlowCredentials {
        val root = runCatching { JsonObjectParser(body).parse() }.getOrNull()
            ?: throw malformedLoginFlowResponse()
        val returnedServer = root.stringValue("server")?.takeIf { it.isNotBlank() }
            ?: throw malformedLoginFlowResponse()
        val username = root.stringValue("loginName")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw malformedLoginFlowResponse()
        val appPassword = root.stringValue("appPassword")?.takeIf { it.isNotBlank() }
            ?: throw malformedLoginFlowResponse()
        val normalizedServer = runCatching { normalizeServer(returnedServer, allowInsecureHttp) }
            .getOrElse { throw loginFlowIdentityFailure() }
        if (!permitsSameScope(requestedServer, normalizedServer, allowInsecureHttp)) {
            throw loginFlowIdentityFailure()
        }
        return NextcloudLoginFlowCredentials(normalizedServer, username, appPassword)
    }

    private fun normalizeServer(raw: String, allowInsecureHttp: Boolean): String {
        return when (val result = NextcloudAddress.normalize(raw, allowInsecureHttp)) {
            is NextcloudAddressResult.Accepted -> result.serverUrl
            is NextcloudAddressResult.Rejected -> throw NextcloudConnectionException(
                NextcloudFailure.Code.INVALID_URL,
                result.message,
            )
        }
    }

    private fun validateServerProvidedUrl(
        raw: String,
        requestedServer: String,
        allowInsecureHttp: Boolean,
        purpose: String,
    ): String {
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in SUPPORTED_SCHEMES || uri.host.isNullOrBlank() ||
            uri.userInfo != null || uri.fragment != null || raw.any(Char::isWhitespace)
        ) {
            throw malformedLoginFlowResponse()
        }
        if (permitsSameScope(requestedServer, raw, allowInsecureHttp)) return raw
        val requested = origin(requestedServer)
        val target = origin(raw)
        if (requested != null && target != null && requested.host == target.host &&
            requested.explicitPort == target.explicitPort &&
            isDowngrade(requested.scheme, target.scheme) && !allowInsecureHttp
        ) {
            throw NextcloudConnectionException(
                NextcloudFailure.Code.INSECURE_REDIRECT,
                "Nextcloud browser authentication attempted to use insecure HTTP for the $purpose endpoint.",
            )
        }
        throw NextcloudConnectionException(
            NextcloudFailure.Code.CROSS_ORIGIN_REDIRECT,
            "Nextcloud browser authentication returned a $purpose endpoint outside the requested server.",
        )
    }

    private fun permitsSameScope(from: String, to: String, allowInsecureHttp: Boolean): Boolean {
        val origin = origin(from) ?: return false
        val target = origin(to) ?: return false
        if (origin.host != target.host) return false
        if (origin.scheme == target.scheme) {
            if (origin.effectivePort != target.effectivePort) return false
        } else {
            if (isDowngrade(origin.scheme, target.scheme) && !allowInsecureHttp) return false
            if (origin.explicitPort != target.explicitPort) return false
        }
        return pathWithinRequestedBase(from, to)
    }

    private fun pathWithinRequestedBase(from: String, to: String): Boolean {
        val basePath = canonicalPath(from) ?: return false
        val targetPath = canonicalPath(to) ?: return false
        return basePath == "/" || targetPath == basePath || targetPath.startsWith("$basePath/")
    }

    private fun canonicalPath(url: String): String? =
        runCatching { URI(url).normalize().path }
            .getOrNull()
            ?.trimEnd('/')
            ?.ifEmpty { "/" }

    private fun origin(url: String): LoginFlowOrigin? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in SUPPORTED_SCHEMES) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val effectivePort = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> return null
        }
        return LoginFlowOrigin(host, scheme, effectivePort, uri.port)
    }

    private fun isDowngrade(fromScheme: String, toScheme: String): Boolean =
        fromScheme == "https" && toScheme == "http"

    private fun loginFlowUnsupported() = NextcloudConnectionException(
        NextcloudFailure.Code.LOGIN_FLOW_UNSUPPORTED,
        "This Nextcloud server does not support browser authentication. Use manual app-password setup.",
    )

    private fun loginFlowFailure(status: Int) = NextcloudConnectionException(
        NextcloudFailure.Code.LOGIN_FLOW_FAILED,
        "Nextcloud browser authentication returned an unexpected server response ($status). Try again or use manual app-password setup.",
    )

    private fun malformedLoginFlowResponse() = NextcloudConnectionException(
        NextcloudFailure.Code.LOGIN_FLOW_MALFORMED,
        "Nextcloud returned an invalid browser-authentication response. Use manual app-password setup.",
    )

    private fun loginFlowIdentityFailure() = NextcloudConnectionException(
        NextcloudFailure.Code.LOGIN_FLOW_IDENTITY,
        "Nextcloud returned account details for a different server. Check the address and try again.",
    )

    private data class StartResponse(
        val loginUrl: String,
        val pollEndpoint: String,
        val pollToken: String,
    )

    private data class LoginFlowOrigin(
        val host: String,
        val scheme: String,
        val effectivePort: Int,
        val explicitPort: Int,
    )

    private sealed interface JsonValue {
        data class Object(val values: Map<String, JsonValue>) : JsonValue
        data class StringValue(val value: String) : JsonValue
    }

    private class JsonObjectParser(private val source: String) {
        private var index = 0

        fun parse(): JsonValue.Object {
            val value = parseValue()
            skipWhitespace()
            if (index != source.length || value !is JsonValue.Object) error("root")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            return when (source.getOrNull(index)) {
                '{' -> parseObject()
                '"' -> JsonValue.StringValue(parseString())
                else -> error("value")
            }
        }

        private fun parseObject(): JsonValue.Object {
            expect('{')
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.Object(values)
            while (true) {
                skipWhitespace()
                val key = parseString()
                if (values.put(key, run { expect(':'); parseValue() }) != null) error("duplicate")
                skipWhitespace()
                when {
                    consume('}') -> return JsonValue.Object(values)
                    consume(',') -> Unit
                    else -> error("separator")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                when (val character = source[index++]) {
                    '"' -> return result.toString()
                    '\\' -> result.append(parseEscape())
                    else -> {
                        if (character.code < 0x20) error("control")
                        result.append(character)
                    }
                }
            }
            error("unterminated")
        }

        private fun parseEscape(): Char {
            return when (val escaped = source.getOrNull(index++) ?: error("escape")) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> source.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
                else -> error("escape")
            }
        }

        private fun expect(expected: Char) {
            if (source.getOrNull(index++) != expected) error("expected")
        }

        private fun consume(expected: Char): Boolean {
            if (source.getOrNull(index) != expected) return false
            index += 1
            return true
        }

        private fun skipWhitespace() {
            while (source.getOrNull(index)?.isWhitespace() == true) index += 1
        }
    }

    private fun JsonValue.Object.stringValue(key: String): String? =
        (values[key] as? JsonValue.StringValue)?.value

    private fun JsonValue.Object.objectValue(key: String): JsonValue.Object? =
        values[key] as? JsonValue.Object

    private companion object {
        const val LOGIN_FLOW_TIMEOUT_MILLIS = 20 * 60 * 1000L
        const val LOGIN_FLOW_POLL_INTERVAL_MILLIS = 1_000L
        val SUPPORTED_SCHEMES = setOf("http", "https")
        val JSON_HEADERS = mapOf("Accept" to "application/json")
        val FORM_HEADERS = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/x-www-form-urlencoded",
        )
    }
}
