package com.kernel.ai.core.memory.nextcloud

import kotlinx.coroutines.test.runTest
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException
import org.junit.jupiter.api.Assertions.assertEquals
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


    private fun credentials() = NextcloudAccountCredentials(NextcloudAccount("https://cloud.example", "alice"), "secret-password")

    private fun response(xml: String) = CalDavResponse(207, mapOf("content-type" to "application/xml"), xml, "")

    private class FakeTransport(private val responses: Map<String, CalDavResponse>) : CalDavTransport {
        val requests = mutableListOf<Request>()

        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): CalDavResponse {
            requests += Request(method, url, headers, body)
            return responses["$method $url"] ?: error("Unexpected request: $method $url")
        }
    }

    private data class Request(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
}
