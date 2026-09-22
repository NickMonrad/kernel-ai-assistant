package com.kernel.ai.core.memory.nextcloud

import java.net.URI
import java.net.URISyntaxException

/** Outcome of turning a user-entered Nextcloud address into a usable server URL. */
sealed interface NextcloudAddressResult {
    /** A URL with an explicit scheme, ready for discovery. */
    data class Accepted(val serverUrl: String) : NextcloudAddressResult

    /** The address cannot be used as given; [message] is safe to show inline next to the field. */
    data class Rejected(val code: Code, val message: String) : NextcloudAddressResult {
        enum class Code {
            BLANK,
            MALFORMED,
            UNSUPPORTED_SCHEME,
            INSECURE_SCHEME,
        }
    }
}

/**
 * Normalizes the free-form "Nextcloud address" field from #1551.
 *
 * HTTPS is always the default: a bare host, a host plus base path, or a host plus port is accepted
 * without a scheme and normalized to `https://`. Plain HTTP is only ever produced for a caller that
 * explicitly opted in through [allowInsecureHttp]; an explicit `http://` address is rejected
 * otherwise rather than being silently rewritten to HTTPS.
 */
object NextcloudAddress {
    private const val HTTPS = "https"
    private const val HTTP = "http"

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://")
    private val HOST_LABEL = Regex("^[A-Za-z0-9_.\\-]+$")

    fun normalize(raw: String, allowInsecureHttp: Boolean = false): NextcloudAddressResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return reject(NextcloudAddressResult.Rejected.Code.BLANK, "Enter a Nextcloud address, for example cloud.example.com.")
        }
        if (trimmed.any(Char::isWhitespace)) {
            return malformed()
        }
        val schemeMatch = SCHEME.find(trimmed)
        val suppliedScheme = schemeMatch?.groupValues?.get(1)?.lowercase()
        if (schemeMatch != null && suppliedScheme != HTTP && suppliedScheme != HTTPS) {
            return reject(
                NextcloudAddressResult.Rejected.Code.UNSUPPORTED_SCHEME,
                "Enter an https:// address or a host name such as cloud.example.com.",
            )
        }
        if (suppliedScheme == HTTP && !allowInsecureHttp) {
            return reject(
                NextcloudAddressResult.Rejected.Code.INSECURE_SCHEME,
                "Plain HTTP is disabled. Enter an https:// address, or enable \"Use insecure HTTP\".",
            )
        }
        val scheme = suppliedScheme ?: HTTPS
        val remainder = if (schemeMatch != null) trimmed.substring(schemeMatch.value.length) else trimmed
        val authorityEnd = remainder.indexOf('/')
        val authority = if (authorityEnd == -1) remainder else remainder.substring(0, authorityEnd)
        val path = if (authorityEnd == -1) "" else remainder.substring(authorityEnd)
        if (authority.isEmpty()) {
            return reject(
                NextcloudAddressResult.Rejected.Code.MALFORMED,
                "Enter a Nextcloud host, for example cloud.example.com.",
            )
        }
        if (authority.contains('@')) {
            return reject(
                NextcloudAddressResult.Rejected.Code.MALFORMED,
                "Enter the Nextcloud address without a username or password.",
            )
        }
        if (path.contains('?') || path.contains('#')) {
            return malformed()
        }
        val hostAndPort = splitHostAndPort(authority) ?: return malformed()
        val host = hostAndPort.first.lowercase()
        if (hostAndPort.first.startsWith("[") && hostAndPort.first.endsWith("]")) {
            // IPv6 literal; no label validation applies and the brackets stay part of the URL.
        } else if (!HOST_LABEL.matches(host) || host.trim('.').isEmpty()) {
            return malformed()
        }
        val port = hostAndPort.second?.let { value ->
            value.toIntOrNull()?.takeIf { it in 1..65535 } ?: return malformed()
        }
        val serverUrl = buildString {
            append(scheme).append("://").append(host)
            if (port != null) append(':').append(port)
            append(path.trimEnd('/'))
        }
        // Final guard: the normalized value must itself be a syntactically valid absolute URL.
        return try {
            URI(serverUrl)
            NextcloudAddressResult.Accepted(serverUrl)
        } catch (_: URISyntaxException) {
            malformed()
        }
    }

    private fun splitHostAndPort(authority: String): Pair<String, String?>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close == -1) return null
            val host = authority.substring(0, close + 1)
            val rest = authority.substring(close + 1)
            return when {
                rest.isEmpty() -> host to null
                rest.startsWith(":") -> host to rest.substring(1)
                else -> null
            }
        }
        val colon = authority.lastIndexOf(':')
        if (colon == -1) return authority to null
        val host = authority.substring(0, colon)
        val port = authority.substring(colon + 1)
        return if (host.isEmpty() || port.isEmpty()) null else host to port
    }

    private fun malformed() =
        reject(NextcloudAddressResult.Rejected.Code.MALFORMED, "Enter a valid Nextcloud address, for example cloud.example.com.")

    private fun reject(code: NextcloudAddressResult.Rejected.Code, message: String) =
        NextcloudAddressResult.Rejected(code, message)
}
