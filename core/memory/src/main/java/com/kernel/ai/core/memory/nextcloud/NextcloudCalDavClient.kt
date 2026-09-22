package com.kernel.ai.core.memory.nextcloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

private const val DAV_NS = "DAV:"
private const val CALDAV_NS = "urn:ietf:params:xml:ns:caldav"
private const val MAX_REDIRECT_HOPS = 5
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

sealed class NextcloudFailure(
    val code: Code,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Code {
        INVALID_ACCOUNT,
        INVALID_URL,
        AUTHENTICATION,
        PERMISSION,
        DISCOVERY,
        MALFORMED_RESPONSE,
        DNS,
        CONNECTION,
        TIMEOUT,
        TLS,
        NETWORK,
        INSECURE_REDIRECT,
        CROSS_ORIGIN_REDIRECT,
        CONFLICT,
        SERVER,
    }
}

class NextcloudConnectionException(
    code: NextcloudFailure.Code,
    message: String,
    cause: Throwable? = null,
) : NextcloudFailure(code, message, cause)
class NextcloudConflictException(message: String = "Nextcloud changed this task while it was being edited") :
    NextcloudFailure(NextcloudFailure.Code.CONFLICT, message)

data class NextcloudCalendarCollection(
    val href: String,
    val displayName: String,
)

data class RemoteVTodo(
    val href: String,
    val etag: String?,
    val document: VTodoDocument,
)

data class NextcloudDiscovery(
    val principalHref: String,
    val calendarHomeHref: String,
    val collections: List<NextcloudCalendarCollection>,
)

interface CalDavTransport {
    suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String? = null): CalDavResponse
}

data class CalDavResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val finalUrl: String,
)

@Singleton
class OkHttpCalDavTransport @Inject constructor() : CalDavTransport {
    /**
     * Redirects are deliberately not followed here. [NextcloudCalDavClient] evaluates every hop so
     * that an HTTPS → HTTP downgrade can be refused for accounts that did not opt in to insecure
     * HTTP (#1551); a client-level policy cannot be expressed with OkHttp's follow flags alone.
     */
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): CalDavResponse =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder().url(url).method(
                    method,
                    body?.toRequestBody("application/xml; charset=utf-8".toMediaType()),
                ).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
            } catch (error: IllegalArgumentException) {
                throw NextcloudConnectionException(
                    NextcloudFailure.Code.INVALID_URL,
                    "The Nextcloud server URL is invalid. Enter a valid URL.",
                    error,
                )
            }
            try {
                client.newCall(request).execute().use { response ->
                    CalDavResponse(
                        status = response.code,
                        headers = response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                        body = response.body?.string().orEmpty(),
                        finalUrl = response.request.url.toString(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw classifyTransportFailure(error)
            }
        }

    internal companion object {
        fun classifyTransportFailure(error: Exception): NextcloudConnectionException {
            val (code, message) = when {
                hasCause(error) { it is UnknownHostException } -> NextcloudFailure.Code.DNS to
                    "Could not find the Nextcloud server. Check the server URL and network connection."
                hasCause(error) { it is SocketTimeoutException } -> NextcloudFailure.Code.TIMEOUT to
                    "The Nextcloud connection timed out. Check the server and network connection."
                hasCause(error) {
                    it is ConnectException || it is NoRouteToHostException || it is SocketException
                } -> NextcloudFailure.Code.CONNECTION to
                    "Could not connect to Nextcloud. Check that the server is reachable."
                hasCause(error) {
                    it is UnknownServiceException &&
                        it.message?.contains("CLEARTEXT", ignoreCase = true) == true
                } -> NextcloudFailure.Code.INSECURE_REDIRECT to
                    "Nextcloud CalDAV discovery redirected to insecure HTTP. Configure the server to keep CalDAV discovery on HTTPS."
                hasCause(error) { it is SSLException } -> NextcloudFailure.Code.TLS to
                    "Could not establish a secure connection to Nextcloud. Check the server certificate."
                else -> NextcloudFailure.Code.NETWORK to
                    "Could not reach Nextcloud. Check the server URL and network connection."
            }
            return NextcloudConnectionException(code, message, error)
        }

        private fun hasCause(error: Throwable, predicate: (Throwable) -> Boolean): Boolean {
            var current: Throwable? = error
            while (current != null) {
                if (predicate(current)) return true
                current = current.cause
            }
            return false
        }
    }
}

/** Standard CalDAV discovery plus the VTODO operations needed by Jandal Lists. */
class NextcloudCalDavClient(
    private val account: NextcloudAccountCredentials,
    private val transport: CalDavTransport,
) {
    suspend fun discover(): NextcloudDiscovery {
        val server = normalizeServer(account.account.serverUrl)
        val wellKnown = send(
            method = "GET",
            url = "$server/.well-known/caldav",
            headers = authHeaders(),
        )
        val endpoint = when {
            wellKnown.response.status in 200..399 -> wellKnown.url
            wellKnown.response.status == 401 || wellKnown.response.status == 403 -> throw authenticationFailure()
            wellKnown.response.status == 404 -> "$server/remote.php/dav"
            else -> throw serverFailure(wellKnown.response.status)
        }
        val principal = propfind(
            endpoint,
            depth = "0",
            body = """
                <d:propfind xmlns:d="$DAV_NS"><d:prop><d:current-user-principal/><d:principal-URL/></d:prop></d:propfind>
            """.trimIndent(),
        ).firstHref("current-user-principal", "principal-URL")
            ?: throw malformed("Nextcloud did not advertise a current-user-principal")
        val principalHref = resolve(endpoint, principal)
        val home = propfind(
            principalHref,
            depth = "0",
            body = """
                <d:propfind xmlns:d="$DAV_NS" xmlns:c="$CALDAV_NS"><d:prop><c:calendar-home-set/></d:prop></d:propfind>
            """.trimIndent(),
        ).firstHref("calendar-home-set")
            ?: throw malformed("Nextcloud did not advertise a calendar-home-set")
        val homeHref = resolve(principalHref, home)
        val collections = propfind(
            homeHref,
            depth = "1",
            body = """
                <d:propfind xmlns:d="$DAV_NS" xmlns:c="$CALDAV_NS"><d:prop>
                    <d:displayname/><d:resourcetype/><c:supported-calendar-component-set/>
                </d:prop></d:propfind>
            """.trimIndent(),
        ).responses.mapNotNull { response ->
            val resourceTypes = response.elements("resourcetype").flatMap { it.childNames() }
            val components = response.elements("supported-calendar-component-set")
                .flatMap { it.elements("comp").mapNotNull { node -> node.getAttribute("name") } }
            if ("calendar" !in resourceTypes || (components.isNotEmpty() && "VTODO" !in components)) return@mapNotNull null
            NextcloudCalendarCollection(
                href = resolve(homeHref, response.href ?: return@mapNotNull null),
                displayName = response.firstText("displayname")?.ifBlank { "Nextcloud Tasks" } ?: "Nextcloud Tasks",
            )
        }.filter { it.href != homeHref }
        return NextcloudDiscovery(principalHref, homeHref, collections)
    }

    suspend fun fetchTasks(collectionHref: String): List<RemoteVTodo> {
        val response = request(
            method = "REPORT",
            url = collectionHref,
            headers = authHeaders() + mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8"),
            body = """
                <c:calendar-query xmlns:c="$CALDAV_NS" xmlns:d="$DAV_NS">
                    <d:prop><d:getetag/><c:calendar-data/></d:prop>
                    <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VTODO"/></c:comp-filter></c:filter>
                </c:calendar-query>
            """.trimIndent(),
        )
        return parseMultistatus(response.response).responses.mapNotNull { item ->
            val data = item.firstText("calendar-data") ?: return@mapNotNull null
            runCatching {
                RemoteVTodo(
                    href = resolve(collectionHref, item.href ?: error("VTODO response has no href")),
                    etag = item.firstText("getetag"),
                    document = VTodoDocument.parse(data),
                )
            }.getOrElse { throw malformed("Nextcloud returned an invalid VTODO") }
        }
    }

    suspend fun putTask(href: String, calendarData: String, etag: String?): String? {
        val response = request(
            method = "PUT",
            url = href,
            headers = authHeaders() + mapOf(
                "Content-Type" to "text/calendar; charset=utf-8",
                "If-Match" to etag?.let(::etagForHeader).orEmpty(),
                "If-None-Match" to if (etag == null) "*" else "",
            ).filterValues { it.isNotEmpty() },
            body = calendarData,
            forbiddenResponse = ForbiddenResponse.PERMISSION,
        )
        if (response.response.status == 412 || response.response.status == 409) throw NextcloudConflictException()
        if (response.response.status !in 200..299) throw serverFailure(response.response.status)
        return response.response.headers.entries.firstOrNull { it.key.equals("etag", ignoreCase = true) }?.value?.trim()
    }

    suspend fun deleteTask(href: String, etag: String?): Boolean {
        val response = request(
            method = "DELETE",
            url = href,
            headers = authHeaders() + mapOf("If-Match" to etag?.let(::etagForHeader).orEmpty())
                .filterValues { it.isNotEmpty() },
            forbiddenResponse = ForbiddenResponse.PERMISSION,
        )
        if (response.response.status == 404) return false
        if (response.response.status == 412 || response.response.status == 409) throw NextcloudConflictException()
        if (response.response.status !in 200..299) throw serverFailure(response.response.status)
        return true
    }

    /**
     * Creates a VTODO collection whose user-visible display name is exactly [title].
     *
     * RFC 4791 requires the property values inside `<d:set><d:prop>`; without that wrapper CalDAV
     * servers (including Nextcloud/sabre) ignore `<d:displayname>` and fall back to the collection
     * URI, which is why the remote list previously appeared as a generated slug (#1551). The
     * generated href stays unique and internal and is never used as the visible name.
     */
    suspend fun createCollection(title: String, calendarHomeHref: String): NextcloudCalendarCollection {
        val href = resolve(calendarHomeHref, "${slug(title)}-${UUID.randomUUID()}/")
        val response = request(
            method = "MKCALENDAR",
            url = href,
            headers = authHeaders() + mapOf("Content-Type" to "application/xml; charset=utf-8"),
            body = """
                <c:mkcalendar xmlns:c="$CALDAV_NS" xmlns:d="$DAV_NS">
                    <d:set><d:prop>
                        <d:displayname>${xmlEscape(title)}</d:displayname>
                        <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
                    </d:prop></d:set>
                </c:mkcalendar>
            """.trimIndent(),
            forbiddenResponse = ForbiddenResponse.PERMISSION,
        )
        if (response.response.status !in 200..299 && response.response.status != 201) {
            throw serverFailure(response.response.status)
        }
        return NextcloudCalendarCollection(href, title)
    }

    private suspend fun propfind(url: String, depth: String, body: String): MultiStatus = parseMultistatus(
        request("PROPFIND", url, authHeaders() + mapOf("Depth" to depth, "Content-Type" to "application/xml; charset=utf-8"), body).response,
    )

    private enum class ForbiddenResponse {
        AUTHENTICATION,
        PERMISSION,
    }

    /** A completed request plus the URL whose response was actually read. */
    private data class Exchange(val response: CalDavResponse, val url: String)

    private suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
        forbiddenResponse: ForbiddenResponse = ForbiddenResponse.AUTHENTICATION,
    ): Exchange {
        val exchange = send(method, url, headers, body)
        if (exchange.response.status == 401) throw authenticationFailure()
        if (exchange.response.status == 403) {
            throw when (forbiddenResponse) {
                ForbiddenResponse.AUTHENTICATION -> authenticationFailure()
                ForbiddenResponse.PERMISSION -> permissionFailure()
            }
        }
        return exchange
    }

    /**
     * Sends one CalDAV request and resolves redirects under this account's origin and scheme policy.
     *
     * The `Authorization` header is reused for every hop, so a redirect is only followed within the
     * same origin — the same host and effective port. A cross-origin redirect is refused rather than
     * forwarded, which is what keeps the Nextcloud Basic credentials from reaching another server.
     * Within that origin, same-scheme hops and HTTP → HTTPS upgrades are followed, and an
     * HTTPS → HTTP downgrade is refused unless the account explicitly enabled insecure HTTP (#1551).
     */
    private suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
    ): Exchange {
        var current = url
        var hops = 0
        while (true) {
            val response = transport.execute(method, current, headers, body)
            if (response.status !in REDIRECT_STATUSES) {
                return Exchange(response, response.finalUrl.ifBlank { current })
            }
            val location = response.headers.entries
                .firstOrNull { it.key.equals("location", ignoreCase = true) }
                ?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return Exchange(response, current)
            val target = runCatching { URI(current).resolve(location).toString() }.getOrNull()
                ?: return Exchange(response, current)
            if (!permitsRedirect(current, target)) throw crossOriginRedirectFailure()
            if (isDowngrade(current, target) && !account.account.allowInsecureHttp) throw insecureRedirectFailure()
            hops += 1
            if (hops > MAX_REDIRECT_HOPS) {
                throw NextcloudConnectionException(
                    NextcloudFailure.Code.DISCOVERY,
                    "Nextcloud redirected this request too many times. Check the server URL.",
                )
            }
            current = target
        }
    }

    /**
     * The part of a URL that may keep receiving this account's credentials.
     *
     * [port] is the effective port and [explicitPort] is the port as written, so a redirect can be
     * judged both on where it really points and on whether it silently changed the port.
     */
    private data class RedirectOrigin(
        val host: String,
        val scheme: String,
        val port: Int,
        val explicitPort: Int,
    )

    /**
     * Whether [from] → [to] may keep this account's `Authorization` header.
     *
     * Another host is never acceptable, and neither is a port change: both would send the Nextcloud
     * app password to a different service. A scheme-only change on the same host stays inside the
     * credential scope and is decided by the downgrade/upgrade policy instead — an HTTP → HTTPS
     * upgrade is always followed, and an HTTPS → HTTP downgrade only for an account that explicitly
     * opted in to insecure HTTP (#1551). A scheme change that also moves the port is refused here.
     */
    private fun permitsRedirect(from: String, to: String): Boolean {
        val origin = redirectOrigin(from) ?: return false
        val target = redirectOrigin(to) ?: return false
        if (origin.host != target.host) return false
        if (origin.scheme == target.scheme) return origin.port == target.port
        return origin.explicitPort == target.explicitPort
    }

    private fun redirectOrigin(url: String): RedirectOrigin? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> return null
        }
        return RedirectOrigin(host, scheme, port, uri.port)
    }

    private fun isDowngrade(from: String, to: String): Boolean =
        from.startsWith("https://", ignoreCase = true) && to.startsWith("http://", ignoreCase = true)

    private fun crossOriginRedirectFailure() = NextcloudConnectionException(
        NextcloudFailure.Code.CROSS_ORIGIN_REDIRECT,
        "Nextcloud redirected the request to a different server. Check the Nextcloud address and CalDAV configuration.",
    )

    private fun insecureRedirectFailure() = NextcloudConnectionException(
        NextcloudFailure.Code.INSECURE_REDIRECT,
        "Nextcloud redirected CalDAV to insecure HTTP. Keep CalDAV on HTTPS, or enable \"Use insecure HTTP\" for this account.",
    )

    private fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to Credentials.basic(account.account.username, account.appPassword),
        "Accept" to "application/xml, text/calendar, text/plain",
    )

    private fun authenticationFailure() = NextcloudConnectionException(
        NextcloudFailure.Code.AUTHENTICATION,
        "Nextcloud rejected the credentials. Use a valid app password and username.",
    )

    private fun permissionFailure() = NextcloudConnectionException(
        NextcloudFailure.Code.PERMISSION,
        "Nextcloud denied write access to this task collection. Check its permissions and try again.",
    )

    private fun etagForHeader(etag: String): String {
        val value = etag.trim()
        return if (value == "*" || value.startsWith("\"") || value.startsWith("W/\"")) {
            value
        } else {
            "\"$value\""
        }
    }

    private fun serverFailure(status: Int) = NextcloudConnectionException(
        if (status >= 500) NextcloudFailure.Code.SERVER else NextcloudFailure.Code.DISCOVERY,
        "Nextcloud returned HTTP $status. Check the server URL and CalDAV permissions.",
    )

    private fun malformed(message: String) = NextcloudConnectionException(NextcloudFailure.Code.MALFORMED_RESPONSE, message)

    private data class MultiStatus(val responses: List<Response>)
    private data class Response(
        val href: String?,
        val properties: List<Element>,
    ) {
        fun firstText(localName: String): String? = properties.firstOrNull { it.localName == localName }?.textContent?.trim()
        fun elements(localName: String): List<Element> = properties.filter { it.localName == localName }
    }

    private fun parseMultistatus(response: CalDavResponse): MultiStatus {
        if (response.status !in 200..299 && response.status != 207) throw serverFailure(response.status)
        try {
            val document = parseSecureXml(response.body)
            val nodes = document.getElementsByTagNameNS(DAV_NS, "response")
            return MultiStatus((0 until nodes.length).map { index ->
                val element = nodes.item(index) as Element
                val props = mutableListOf<Element>()
                val propNodes = element.getElementsByTagNameNS(DAV_NS, "prop")
                if (propNodes.length > 0) {
                    val children = propNodes.item(0).childNodes
                    for (childIndex in 0 until children.length) {
                        (children.item(childIndex) as? Element)?.let(props::add)
                    }
                }
                Response(
                    href = element.elements("href").firstOrNull()?.textContent?.trim(),
                    properties = props,
                )
            })
        } catch (error: NextcloudFailure) {
            throw error
        } catch (_: Exception) {
            throw malformed("Nextcloud returned malformed CalDAV XML")
        }
    }

    private fun MultiStatus.firstHref(vararg names: String): String? = responses.asSequence()
        .flatMap { response -> names.asSequence().mapNotNull { response.firstText(it) } }
        .firstOrNull()

    private fun Element.childNames(): List<String> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .map { it.localName ?: it.nodeName.substringAfter(':') }


    private fun normalizeServer(server: String): String = server.trim().removeSuffix("/")
    private fun resolve(base: String, child: String): String = URI(base).resolve(child).toString()
    private fun slug(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "jandal-tasks" }
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
private fun Node.elements(localName: String): List<Element> = (0 until childNodes.length)
    .mapNotNull { childNodes.item(it) as? Element }
    .filter { (it.localName ?: it.nodeName.substringAfter(':')) == localName }

internal fun parseSecureXml(body: String): Document {
    if (body.contains("<!DOCTYPE", ignoreCase = true)) {
        throw SAXException("DOCTYPE is not permitted")
    }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        trySetFeature("http://xml.org/sax/features/external-general-entities", false)
        trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        try {
            isXIncludeAware = false
        } catch (_: UnsupportedOperationException) {
            // Android's XML implementation does not expose XInclude configuration.
        }
        try {
            isExpandEntityReferences = false
        } catch (_: UnsupportedOperationException) {
            // Android's XML implementation does not expose entity expansion configuration.
        }
    }
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray()))
}

private fun DocumentBuilderFactory.trySetFeature(feature: String, value: Boolean) {
    try {
        setFeature(feature, value)
    } catch (_: ParserConfigurationException) {
        // Android's XML implementation rejects these JVM-oriented feature URIs.
    }
}
