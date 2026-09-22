package com.kernel.ai.core.memory.nextcloud

import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NextcloudCalDavClientTest {
    @Test
    fun `discovery follows well-known principal and calendar home`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "GET https://cloud.example/.well-known/caldav" to CalDavResponse(200, emptyMap(), "", "https://cloud.example/remote.php/dav"),
                "PROPFIND https://cloud.example/remote.php/dav" to response(
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal></d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
                "PROPFIND https://cloud.example/remote.php/dav/principals/users/alice/" to response(
                    """
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop><c:calendar-home-set><d:href>/remote.php/dav/calendars/alice/</d:href></c:calendar-home-set></d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
                "PROPFIND https://cloud.example/remote.php/dav/calendars/alice/" to response(
                    """
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/remote.php/dav/calendars/alice/work/</d:href><d:propstat><d:prop><d:displayname>Work</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
            ),
        )
        val discovery = NextcloudCalDavClient(credentials(), transport).discover()
        assertEquals("https://cloud.example/remote.php/dav/principals/users/alice/", discovery.principalHref)
        assertEquals("https://cloud.example/remote.php/dav/calendars/alice/", discovery.calendarHomeHref)
        assertEquals(listOf("Work"), discovery.collections.map { it.displayName })
        assertTrue(transport.requests.all { it.headers["Authorization"]?.startsWith("Basic ") == true })
    }

    @Test
    fun `transport failures map to safe categories and preserve causes`() {
        val cases = listOf(
            SSLHandshakeException("private.example") to NextcloudFailure.Code.TLS,
            UnknownHostException("private.example") to NextcloudFailure.Code.DNS,
            ConnectException("private.example") to NextcloudFailure.Code.CONNECTION,
            SocketException("private.example") to NextcloudFailure.Code.CONNECTION,
            SocketTimeoutException("private.example") to NextcloudFailure.Code.TIMEOUT,
        )

        cases.forEach { (cause, expectedCode) ->
            val failure = OkHttpCalDavTransport.classifyTransportFailure(cause)

            assertEquals(expectedCode, failure.code)
            assertSame(cause, failure.cause)
            assertFalse(failure.message.contains("private.example"))
        }
    }

    @Test
    fun `cleartext redirect failures use an actionable message and preserve cause`() {
        val cause = UnknownServiceException(
            "CLEARTEXT communication to redirect target not permitted by network security policy",
        )

        val failure = OkHttpCalDavTransport.classifyTransportFailure(cause)

        assertEquals(NextcloudFailure.Code.INSECURE_REDIRECT, failure.code)
        assertEquals(
            "Nextcloud CalDAV discovery redirected to insecure HTTP. Configure the server to keep CalDAV discovery on HTTPS.",
            failure.message,
        )
        assertSame(cause, failure.cause)
    }

    @Test
    fun `other unknown service failures remain generic network failures`() {
        val cause = UnknownServiceException("unsupported protocol")

        val failure = OkHttpCalDavTransport.classifyTransportFailure(cause)

        assertEquals(NextcloudFailure.Code.NETWORK, failure.code)
        assertSame(cause, failure.cause)
    }

    @Test
    fun `malformed URL is classified without exposing the invalid value`() = runTest {
        val error = runCatching {
            OkHttpCalDavTransport().execute("GET", "not a URL", emptyMap())
        }.exceptionOrNull()

        assertTrue(error is NextcloudConnectionException)
        assertEquals(NextcloudFailure.Code.INVALID_URL, (error as NextcloudConnectionException).code)
        assertTrue(error.cause is IllegalArgumentException)
        assertFalse(error.message.contains("not a URL"))
    }


    @Test
    fun `conditional write surfaces conflicts`() = runTest {
        val transport = FakeTransport(
            mapOf("PUT https://cloud.example/tasks/1.ics" to CalDavResponse(412, emptyMap(), "", "https://cloud.example/tasks/1.ics")),
        )
        val client = NextcloudCalDavClient(credentials(), transport)
        val error = runCatching {
            client.putTask("https://cloud.example/tasks/1.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", "etag-1")
        }.exceptionOrNull()
        assertTrue(error is NextcloudConflictException)
        assertEquals("\"etag-1\"", transport.requests.single().headers["If-Match"])
    }
    @Test
    fun `new task preserves the server etag and uses create precondition`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "PUT https://cloud.example/tasks/new.ics" to
                    CalDavResponse(201, mapOf("ETag" to "\"new-etag\""), "", "https://cloud.example/tasks/new.ics"),
            ),
        )
        val client = NextcloudCalDavClient(credentials(), transport)
        assertEquals(
            "\"new-etag\"",
            client.putTask("https://cloud.example/tasks/new.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", null),
        )
        assertEquals("*", transport.requests.single().headers["If-None-Match"])
        assertTrue("If-Match" !in transport.requests.single().headers)
    }


    @Test
    fun `authentication failure is actionable and does not expose password`() = runTest {
        val transport = FakeTransport(
            mapOf("GET https://cloud.example/.well-known/caldav" to CalDavResponse(401, emptyMap(), "secret-password", "https://cloud.example/.well-known/caldav")),
        )
        val error = runCatching {
            NextcloudCalDavClient(credentials(), transport).discover()
        }.exceptionOrNull()
        assertTrue(error is NextcloudConnectionException)
        assertEquals(NextcloudFailure.Code.AUTHENTICATION, (error as NextcloudConnectionException).code)
        assertTrue(!error.message.contains("secret-password"))
    }

    @Test
    fun `forbidden task write is reported as a permission failure`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "PUT https://cloud.example/tasks/1.ics" to
                    CalDavResponse(403, emptyMap(), "private response", "https://cloud.example/tasks/1.ics"),
            ),
        )

        val error = runCatching {
            NextcloudCalDavClient(credentials(), transport)
                .putTask("https://cloud.example/tasks/1.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", "\"etag-1\"")
        }.exceptionOrNull()

        assertTrue(error is NextcloudConnectionException)
        assertEquals(NextcloudFailure.Code.PERMISSION, (error as NextcloudConnectionException).code)
        assertFalse(error.message.contains("private response"))
    }

    @Test
    fun `forbidden discovery response remains an authentication failure`() = runTest {
        val transport = FakeTransport(
            mapOf("GET https://cloud.example/.well-known/caldav" to CalDavResponse(403, emptyMap(), "", "")),
        )

        val error = runCatching {
            NextcloudCalDavClient(credentials(), transport).discover()
        }.exceptionOrNull()

        assertTrue(error is NextcloudConnectionException)
        assertEquals(NextcloudFailure.Code.AUTHENTICATION, (error as NextcloudConnectionException).code)
    }

    @Test
    fun `server discovery response remains a server failure`() = runTest {
        val transport = FakeTransport(
            mapOf("GET https://cloud.example/.well-known/caldav" to CalDavResponse(503, emptyMap(), "", "")),
        )

        val error = runCatching {
            NextcloudCalDavClient(credentials(), transport).discover()
        }.exceptionOrNull()

        assertTrue(error is NextcloudConnectionException)
        assertEquals(NextcloudFailure.Code.SERVER, (error as NextcloudConnectionException).code)
    }


    // ── Visible collection naming (#1551) ────────────────────────────────────────────────────────

    @Test
    fun `a created collection sends the clean list title as its display name`() = runTest {
        val home = "https://cloud.example/remote.php/dav/calendars/alice/"
        val transport = FakeTransport(
            mapOf("MKCALENDAR *" to CalDavResponse(201, emptyMap(), "", home)),
        )
        val client = NextcloudCalDavClient(credentials(), transport)

        val first = client.createCollection("Shopping", home)
        val second = client.createCollection("Shopping", home)

        assertEquals("Shopping", first.displayName)
        assertEquals("Shopping", second.displayName)
        assertNotEquals(first.href, second.href, "internal hrefs must stay unique")

        val body = transport.requests.first().body.orEmpty()
        assertTrue(body.contains("<d:displayname>Shopping</d:displayname>"), body)
        assertTrue(
            body.contains("<d:set>") && body.contains("<d:prop>"),
            "RFC 4791 requires the DAV set/prop wrapper for the server to apply the display name",
        )
        // Uniqueness lives only in the internal href; nothing identifier-shaped reaches the name.
        val hrefSlug = first.href.removePrefix(home)
        assertFalse(body.contains(hrefSlug), "the generated href must not appear in the request properties")
        assertFalse(body.contains("displayname>Shopping-"), "the visible name carries no identifier suffix")
    }

    // ── Scheme policy (#1551) ───────────────────────────────────────────────────────────────────

    @Test
    fun `a cross-origin redirect is refused and never receives the credentials`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "GET https://cloud.example/.well-known/caldav" to CalDavResponse(
                    status = 302,
                    headers = mapOf("Location" to "https://attacker.example/remote.php/dav"),
                    body = "",
                    finalUrl = "https://cloud.example/.well-known/caldav",
                ),
            ),
        )

        val error = runCatching {
            NextcloudCalDavClient(credentials(), transport).discover()
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.CROSS_ORIGIN_REDIRECT, (error as NextcloudConnectionException).code)
        assertTrue(
            transport.requests.none { it.url.contains("attacker.example") },
            "the redirected host must never be requested",
        )
        assertTrue(
            transport.requests.all { it.headers["Authorization"] == null || it.url.startsWith("https://cloud.example") },
            "credentials may only ever accompany a request to the account's own origin",
        )
        assertFalse(error.message.contains("attacker.example"), "the refusal must not echo the redirect target")
    }

    @Test
    fun `a redirect to another port on the same host is refused`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "GET https://cloud.example/.well-known/caldav" to CalDavResponse(
                    status = 302,
                    headers = mapOf("Location" to "https://cloud.example:8443/remote.php/dav"),
                    body = "",
                    finalUrl = "https://cloud.example/.well-known/caldav",
                ),
            ),
        )

        val error = runCatching {
            NextcloudCalDavClient(credentials(), transport).discover()
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.CROSS_ORIGIN_REDIRECT, (error as NextcloudConnectionException).code)
        assertTrue(transport.requests.none { it.url.contains(":8443") })
    }

    @Test
    fun `a same-origin redirect keeps working and keeps the credentials`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "GET https://cloud.example/.well-known/caldav" to CalDavResponse(
                    status = 301,
                    headers = mapOf("Location" to "https://cloud.example/remote.php/dav"),
                    body = "",
                    finalUrl = "https://cloud.example/.well-known/caldav",
                ),
                "GET https://cloud.example/remote.php/dav" to CalDavResponse(
                    status = 200,
                    headers = emptyMap(),
                    body = "",
                    finalUrl = "https://cloud.example/remote.php/dav",
                ),
                "PROPFIND https://cloud.example/remote.php/dav" to response(
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav</d:href><d:propstat><d:prop>
                    <d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal>
                    </d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
                "PROPFIND https://cloud.example/remote.php/dav/principals/users/alice/" to response(
                    """
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response>
                    <d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop>
                    <c:calendar-home-set><d:href>/remote.php/dav/calendars/alice/</d:href></c:calendar-home-set>
                    </d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
                "PROPFIND https://cloud.example/remote.php/dav/calendars/alice/" to response(
                    """
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response>
                    <d:href>/remote.php/dav/calendars/alice/tasks/</d:href><d:propstat><d:prop>
                    <d:displayname>Tasks</d:displayname>
                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                    <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
                    </d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
            ),
        )

        val discovery = NextcloudCalDavClient(credentials(), transport).discover()

        assertEquals(listOf("Tasks"), discovery.collections.map { it.displayName })
        val followed = transport.requests.single {
            it.method == "GET" && it.url == "https://cloud.example/remote.php/dav"
        }
        assertTrue(
            followed.headers["Authorization"]?.startsWith("Basic ") == true,
            "a same-origin hop keeps the credentials",
        )
    }

    @Test
    fun `discovery refuses an https to http downgrade and never calls the plaintext url`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "GET https://cloud.example/.well-known/caldav" to CalDavResponse(
                    status = 302,
                    headers = mapOf("Location" to "http://cloud.example/remote.php/dav"),
                    body = "",
                    finalUrl = "https://cloud.example/.well-known/caldav",
                ),
            ),
        )

        val error = runCatching {
            NextcloudCalDavClient(credentials(), transport).discover()
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.INSECURE_REDIRECT, (error as NextcloudConnectionException).code)
        assertTrue(transport.requests.none { it.url.startsWith("http://") })
    }

    @Test
    fun `discovery follows an http to https upgrade`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "GET http://cloud.example/.well-known/caldav" to CalDavResponse(
                    status = 301,
                    headers = mapOf("Location" to "https://cloud.example/remote.php/dav"),
                    body = "",
                    finalUrl = "http://cloud.example/.well-known/caldav",
                ),
                "GET https://cloud.example/remote.php/dav" to CalDavResponse(
                    status = 200,
                    headers = emptyMap(),
                    body = "",
                    finalUrl = "https://cloud.example/remote.php/dav",
                ),
                "PROPFIND https://cloud.example/remote.php/dav" to response(
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav</d:href><d:propstat><d:prop>
                    <d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal>
                    </d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
                "PROPFIND https://cloud.example/remote.php/dav/principals/users/alice/" to response(
                    """
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response>
                    <d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop>
                    <c:calendar-home-set><d:href>/remote.php/dav/calendars/alice/</d:href></c:calendar-home-set>
                    </d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
                "PROPFIND https://cloud.example/remote.php/dav/calendars/alice/" to response(
                    """
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response>
                    <d:href>/remote.php/dav/calendars/alice/tasks/</d:href><d:propstat><d:prop>
                    <d:displayname>Tasks</d:displayname>
                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                    <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
                    </d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
            ),
        )

        val discovery = NextcloudCalDavClient(
            NextcloudAccountCredentials(
                NextcloudAccount("http://cloud.example", "alice", allowInsecureHttp = true),
                "secret-password",
            ),
            transport,
        ).discover()

        assertEquals(listOf("Tasks"), discovery.collections.map { it.displayName })
        assertTrue(transport.requests.any { it.url.startsWith("https://cloud.example/") })
    }

    @Test
    fun `an opted-in account follows an https to http downgrade`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "GET https://cloud.example/.well-known/caldav" to CalDavResponse(
                    status = 301,
                    headers = mapOf("Location" to "http://cloud.example/remote.php/dav"),
                    body = "",
                    finalUrl = "https://cloud.example/.well-known/caldav",
                ),
                "GET http://cloud.example/remote.php/dav" to CalDavResponse(
                    status = 200,
                    headers = emptyMap(),
                    body = "",
                    finalUrl = "http://cloud.example/remote.php/dav",
                ),
                "PROPFIND http://cloud.example/remote.php/dav" to response(
                    """
                    <d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav</d:href><d:propstat><d:prop>
                    <d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal>
                    </d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
                "PROPFIND http://cloud.example/remote.php/dav/principals/users/alice/" to response(
                    """
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response>
                    <d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop>
                    <c:calendar-home-set><d:href>/remote.php/dav/calendars/alice/</d:href></c:calendar-home-set>
                    </d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
                "PROPFIND http://cloud.example/remote.php/dav/calendars/alice/" to response(
                    """
                    <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response>
                    <d:href>/remote.php/dav/calendars/alice/tasks/</d:href><d:propstat><d:prop>
                    <d:displayname>Tasks</d:displayname>
                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                    <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
                    </d:prop></d:propstat></d:response></d:multistatus>
                    """,
                ),
            ),
        )

        val discovery = NextcloudCalDavClient(
            NextcloudAccountCredentials(
                NextcloudAccount("https://cloud.example", "alice", allowInsecureHttp = true),
                "secret-password",
            ),
            transport,
        ).discover()

        assertEquals(listOf("Tasks"), discovery.collections.map { it.displayName })
        assertTrue(transport.requests.any { it.url == "http://cloud.example/remote.php/dav" })
    }

    @Test
    fun `a redirect loop fails closed instead of retrying forever`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "GET https://cloud.example/.well-known/caldav" to CalDavResponse(
                    status = 301,
                    headers = mapOf("Location" to "https://cloud.example/loop"),
                    body = "",
                    finalUrl = "https://cloud.example/.well-known/caldav",
                ),
                "GET https://cloud.example/loop" to CalDavResponse(
                    status = 301,
                    headers = mapOf("Location" to "https://cloud.example/loop"),
                    body = "",
                    finalUrl = "https://cloud.example/loop",
                ),
            ),
        )

        val error = runCatching {
            NextcloudCalDavClient(credentials(), transport).discover()
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.DISCOVERY, (error as NextcloudConnectionException).code)
    }

    private fun credentials() = NextcloudAccountCredentials(NextcloudAccount("https://cloud.example", "alice"), "secret-password")

    private fun response(xml: String) = CalDavResponse(207, mapOf("content-type" to "application/xml"), xml, "")

    private class FakeTransport(private val responses: Map<String, CalDavResponse>) : CalDavTransport {
        val requests = mutableListOf<Request>()

        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): CalDavResponse {
            requests += Request(method, url, headers, body)
            return responses["$method $url"] ?: responses["$method *"] ?: error("Unexpected request: $method $url")
        }
    }

    private data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
}
