package com.kernel.ai.core.memory.nextcloud

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val LOGIN_SERVER = "https://cloud.example/nextcloud"
private const val LOGIN_URL = "$LOGIN_SERVER/login/v2/flow/session"
private const val POLL_URL = "$LOGIN_SERVER/login/v2/poll"

class NextcloudLoginFlowClientTest {
    @Test
    fun `initiation parses browser URL and exact poll endpoint before success`() = runTest {
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response(
                    """{"poll":{"token":"flow-token","endpoint":"$POLL_URL"},"login":"$LOGIN_URL"}""",
                ),
            )
            enqueue("POST", POLL_URL, CalDavResponse(404, emptyMap(), "", POLL_URL))
            enqueue(
                "POST",
                POLL_URL,
                response("""{"server":"$LOGIN_SERVER","loginName":"alice","appPassword":"app-password"}"""),
            )
        }
        val opened = mutableListOf<String>()

        val credentials = NextcloudLoginFlowClient(
            transport = transport,
            timeoutMillis = 1_000L,
            pollIntervalMillis = 1L,
        ).authenticate(LOGIN_SERVER, onLoginUrl = { opened += it })

        assertEquals(LOGIN_URL, opened.single())
        assertEquals(LOGIN_SERVER, credentials.serverUrl)
        assertEquals("alice", credentials.username)
        assertEquals("app-password", credentials.appPassword)
        assertEquals(listOf("POST", "POST", "POST"), transport.requests.map { it.method })
        assertEquals(POLL_URL, transport.requests[1].url)
        assertEquals(POLL_URL, transport.requests[2].url)
        assertEquals("application/x-www-form-urlencoded", transport.requests[1].headers["Content-Type"])
        assertTrue(transport.requests[1].body?.startsWith("token=") == true)
    }

    @Test
    fun `pending poll then transient DNS then success returns credentials`() = runTest {
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response("""{"poll":{"token":"flow-token","endpoint":"$POLL_URL"},"login":"$LOGIN_URL"}"""),
            )
            enqueue("POST", POLL_URL, CalDavResponse(404, emptyMap(), "", POLL_URL))
            enqueueFailure("POST", POLL_URL, NextcloudFailure.Code.DNS)
            enqueue(
                "POST",
                POLL_URL,
                response("""{"server":"$LOGIN_SERVER","loginName":"alice","appPassword":"app-password"}"""),
            )
        }

        val credentials = NextcloudLoginFlowClient(
            transport = transport,
            timeoutMillis = 1_000L,
            pollIntervalMillis = 1L,
        ).authenticate(LOGIN_SERVER)

        assertEquals(LOGIN_SERVER, credentials.serverUrl)
        assertEquals("alice", credentials.username)
        assertEquals("app-password", credentials.appPassword)
    }

    @Test
    fun `transient DNS poll retry reuses the same endpoint and token without restarting`() = runTest {
        val initiationUrl = "$LOGIN_SERVER/index.php/login/v2"
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                initiationUrl,
                response("""{"poll":{"token":"flow-token","endpoint":"$POLL_URL"},"login":"$LOGIN_URL"}"""),
            )
            enqueue("POST", POLL_URL, CalDavResponse(404, emptyMap(), "", POLL_URL))
            enqueueFailure("POST", POLL_URL, NextcloudFailure.Code.DNS)
            enqueue(
                "POST",
                POLL_URL,
                response("""{"server":"$LOGIN_SERVER","loginName":"alice","appPassword":"app-password"}"""),
            )
        }

        NextcloudLoginFlowClient(
            transport = transport,
            timeoutMillis = 1_000L,
            pollIntervalMillis = 1L,
        ).authenticate(LOGIN_SERVER)

        val initiationRequests = transport.requests.filter { it.url == initiationUrl }
        val pollRequests = transport.requests.filter { it.url == POLL_URL }
        assertEquals(1, initiationRequests.size)
        assertEquals(3, pollRequests.size)
        assertTrue(pollRequests.all { it.url == POLL_URL })
        assertEquals(pollRequests.first().body, pollRequests.last().body)
    }


    @Test
    fun `initiation sends a real zero length POST body`() = runTest {
        MockWebServer().use { server ->
            val serverUrl = server.url("/nextcloud").toString().removeSuffix("/")
            val loginUrl = server.url("/nextcloud/login/v2/flow").toString()
            val pollUrl = server.url("/nextcloud/login/v2/poll").toString()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"poll":{"token":"fixture-token","endpoint":"$pollUrl"},"login":"$loginUrl"}""",
                    ),
            )
            val authentication = launch {
                NextcloudLoginFlowClient(
                    transport = OkHttpCalDavTransport(),
                    timeoutMillis = 10_000L,
                    pollIntervalMillis = 1_000L,
                ).authenticate(serverUrl, allowInsecureHttp = true)
            }
            runCurrent()

            val initiation = server.takeRequest(1L, TimeUnit.SECONDS)
                ?: error("Login Flow initiation request was not received")
            authentication.cancel()
            authentication.join()

            assertEquals("POST", initiation.method)
            assertEquals(0L, initiation.body.size)
            assertEquals("", initiation.body.readUtf8())
        }
    }

    @Test
    fun `unsupported server exposes manual fallback error without response details`() = runTest {
        val responseBody = "unsupported-private-response"
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                CalDavResponse(404, emptyMap(), responseBody, "$LOGIN_SERVER/index.php/login/v2"),
            )
        }

        val error = runCatching {
            NextcloudLoginFlowClient(transport, timeoutMillis = 100L).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.LOGIN_FLOW_UNSUPPORTED, (error as NextcloudFailure).code)
        assertFalse(error.message.contains(responseBody))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `terminal poll failure stops without retrying`() = runTest {
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response("""{"poll":{"token":"flow-token","endpoint":"$POLL_URL"},"login":"$LOGIN_URL"}"""),
            )
            enqueue("POST", POLL_URL, CalDavResponse(403, emptyMap(), "private-response", POLL_URL))
        }

        val error = runCatching {
            NextcloudLoginFlowClient(transport, timeoutMillis = 1_000L).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.AUTHENTICATION, (error as NextcloudFailure).code)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `repeated DNS poll failures remain bounded by the existing timeout`() = runTest {
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response("""{"poll":{"token":"flow-token","endpoint":"$POLL_URL"},"login":"$LOGIN_URL"}"""),
            )
            repeat(10) {
                enqueueFailure("POST", POLL_URL, NextcloudFailure.Code.DNS)
            }
        }

        val error = runCatching {
            NextcloudLoginFlowClient(
                transport = transport,
                timeoutMillis = 20L,
                pollIntervalMillis = 10L,
            ).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.TIMEOUT, (error as NextcloudFailure).code)
        assertTrue(transport.requests.size >= 2)
    }

    @Test
    fun `malformed initiation is rejected without echoing payload`() = runTest {
        val payload = "secret-flow-token"
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response("""{"poll":{"token":"$payload"}}"""),
            )
        }

        val error = runCatching {
            NextcloudLoginFlowClient(transport, timeoutMillis = 100L).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.LOGIN_FLOW_MALFORMED, (error as NextcloudFailure).code)
        assertFalse(error.message.contains(payload))
    }

    @Test
    fun `cross-origin poll endpoint is rejected before token submission`() = runTest {
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response(
                    """{"poll":{"token":"flow-token","endpoint":"https://attacker.example/poll"},"login":"$LOGIN_URL"}""",
                ),
            )
        }

        val error = runCatching {
            NextcloudLoginFlowClient(transport, timeoutMillis = 100L).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.CROSS_ORIGIN_REDIRECT, (error as NextcloudFailure).code)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `same host outside the requested base path is rejected`() = runTest {
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response(
                    """{"poll":{"token":"flow-token","endpoint":"https://cloud.example/other/login/v2/poll"},"login":"$LOGIN_URL"}""",
                ),
            )
        }

        val error = runCatching {
            NextcloudLoginFlowClient(transport, timeoutMillis = 100L).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.CROSS_ORIGIN_REDIRECT, (error as NextcloudFailure).code)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `dot segment escape outside the requested base path is rejected`() = runTest {
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response(
                    """{"poll":{"token":"flow-token","endpoint":"https://cloud.example/nextcloud/../outside/login/v2/poll"},"login":"$LOGIN_URL"}""",
                ),
            )
        }

        val error = runCatching {
            NextcloudLoginFlowClient(transport, timeoutMillis = 100L).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.CROSS_ORIGIN_REDIRECT, (error as NextcloudFailure).code)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `https downgrade is rejected unless insecure HTTP is explicitly enabled`() = runTest {
        val insecureLogin = "http://cloud.example/nextcloud/login/v2/flow/session"
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response(
                    """{"poll":{"token":"flow-token","endpoint":"$POLL_URL"},"login":"$insecureLogin"}""",
                ),
            )
        }

        val error = runCatching {
            NextcloudLoginFlowClient(transport, timeoutMillis = 100L).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.INSECURE_REDIRECT, (error as NextcloudFailure).code)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `explicit insecure opt in permits same-scope HTTP Login Flow endpoints`() = runTest {
        val insecurePoll = "http://cloud.example/nextcloud/login/v2/poll"
        val insecureLogin = "http://cloud.example/nextcloud/login/v2/flow/session"
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response(
                    """{"poll":{"token":"flow-token","endpoint":"$insecurePoll"},"login":"$insecureLogin"}""",
                ),
            )
            enqueue(
                "POST",
                insecurePoll,
                response(
                    """{"server":"http://cloud.example/nextcloud","loginName":"alice","appPassword":"app-password"}""",
                ),
            )
        }

        val credentials = NextcloudLoginFlowClient(transport, timeoutMillis = 100L)
            .authenticate(LOGIN_SERVER, allowInsecureHttp = true)

        assertEquals("http://cloud.example/nextcloud", credentials.serverUrl)
        assertEquals(insecurePoll, transport.requests[1].url)
    }

    @Test
    fun `returned server identity must stay within requested account scope`() = runTest {
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response("""{"poll":{"token":"flow-token","endpoint":"$POLL_URL"},"login":"$LOGIN_URL"}"""),
            )
            enqueue(
                "POST",
                POLL_URL,
                response("""{"server":"https://other.example","loginName":"alice","appPassword":"app-password"}"""),
            )
        }

        val error = runCatching {
            NextcloudLoginFlowClient(transport, timeoutMillis = 100L).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.LOGIN_FLOW_IDENTITY, (error as NextcloudFailure).code)
        assertFalse(error.message.contains("other.example"))
    }

    @Test
    fun `timeout is bounded while pending responses continue`() = runTest {
        val transport = QueueTransport().apply {
            enqueue(
                "POST",
                "$LOGIN_SERVER/index.php/login/v2",
                response("""{"poll":{"token":"flow-token","endpoint":"$POLL_URL"},"login":"$LOGIN_URL"}"""),
            )
            repeat(5) {
                enqueue("POST", POLL_URL, CalDavResponse(404, emptyMap(), "", POLL_URL))
            }
        }

        val error = runCatching {
            NextcloudLoginFlowClient(
                transport = transport,
                timeoutMillis = 20L,
                pollIntervalMillis = 10L,
            ).authenticate(LOGIN_SERVER)
        }.exceptionOrNull()

        assertEquals(NextcloudFailure.Code.TIMEOUT, (error as NextcloudFailure).code)
        assertTrue(transport.requests.size >= 2)
    }

    @Test
    fun `cancellation propagates and stops polling`() = runTest {
        val pollStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transport = object : CalDavTransport {
            var pollRequests = 0

            override suspend fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String?,
            ): CalDavResponse {
                return if (url.endsWith("/index.php/login/v2")) {
                    response("""{"poll":{"token":"flow-token","endpoint":"$POLL_URL"},"login":"$LOGIN_URL"}""")
                } else {
                    pollRequests += 1
                    pollStarted.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val job = launch {
            NextcloudLoginFlowClient(transport, timeoutMillis = 1_000L).authenticate(LOGIN_SERVER)
        }

        pollStarted.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, transport.pollRequests)
    }

    private fun response(body: String) = CalDavResponse(200, mapOf("content-type" to "application/json"), body, "")

    private class QueueTransport : CalDavTransport {
        val requests = mutableListOf<Request>()
        private val responses = linkedMapOf<String, ArrayDeque<Outcome>>()

        fun enqueue(method: String, url: String, response: CalDavResponse) {
            responses.getOrPut("$method $url") { ArrayDeque() }.addLast(Outcome.Response(response))
        }

        fun enqueueFailure(method: String, url: String, code: NextcloudFailure.Code) {
            responses.getOrPut("$method $url") { ArrayDeque() }.addLast(
                Outcome.Failure(NextcloudConnectionException(code, "test failure")),
            )
        }

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): CalDavResponse {
            requests += Request(method, url, headers, body)
            return when (val outcome = responses["$method $url"]?.removeFirstOrNull()) {
                is Outcome.Response -> outcome.value
                is Outcome.Failure -> throw outcome.error
                null -> error("Unexpected Login Flow request")
            }
        }

        private sealed interface Outcome {
            data class Response(val value: CalDavResponse) : Outcome

            data class Failure(val error: NextcloudFailure) : Outcome
        }
    }

    private data class Request(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String?,
    )
}
