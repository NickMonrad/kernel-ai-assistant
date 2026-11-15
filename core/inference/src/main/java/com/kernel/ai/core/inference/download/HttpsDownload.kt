package com.kernel.ai.core.inference.download

import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/** A model or voice download URL, or one of its redirect hops, is not HTTPS (#1678). */
class InsecureDownloadUrlException(message: String) : IOException(message)

private const val MAX_DOWNLOAD_REDIRECTS = 10
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

/**
 * Opens an HTTPS download connection, following redirects one hop at a time so that every hop can
 * be checked: a non-HTTPS start URL or any redirect to a non-HTTPS URL is refused with
 * [InsecureDownloadUrlException] before a request is sent to it (#1678).
 *
 * [headers] (for example `Range` for resume) are sent on every hop. `Authorization` is only sent
 * while the host is unchanged, matching how the platform client drops credentials when a redirect
 * moves to another host such as a CDN.
 *
 * The returned connection has already received a non-redirect response; the caller checks its
 * status and reads the body.
 */
fun openHttpsDownload(
    url: String,
    headers: Map<String, String> = emptyMap(),
    open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
): HttpURLConnection {
    val start = try {
        URL(url)
    } catch (error: MalformedURLException) {
        throw IOException("Invalid download URL", error)
    }
    var current = requireHttps(start, "Download URL must use HTTPS")
    var redirects = 0
    while (true) {
        val connection = open(current)
        connection.instanceFollowRedirects = false
        headers.forEach { (name, value) ->
            val isCredential = name.equals("Authorization", ignoreCase = true)
            if (!isCredential || current.host.equals(start.host, ignoreCase = true)) {
                connection.setRequestProperty(name, value)
            }
        }
        connection.connect()
        if (connection.responseCode !in REDIRECT_STATUSES) return connection
        val location = connection.getHeaderField("Location")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return connection
        connection.disconnect()
        val next = try {
            URL(current, location)
        } catch (error: MalformedURLException) {
            throw IOException("Download redirect has an invalid location", error)
        }
        current = requireHttps(next, "Download redirected to insecure HTTP")
        redirects += 1
        if (redirects > MAX_DOWNLOAD_REDIRECTS) throw IOException("Download redirected too many times")
    }
}

private fun requireHttps(url: URL, message: String): URL {
    if (!url.protocol.equals("https", ignoreCase = true)) throw InsecureDownloadUrlException(message)
    return url
}
