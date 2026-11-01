package com.kernel.ai.core.inference.download

import java.net.HttpURLConnection
import java.net.URL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpsDownloadTest {
    /** Records what a hop was sent without touching the network. */
    private class FakeConnection(
        url: URL,
        private val status: Int,
        private val location: String? = null,
    ) : HttpURLConnection(url) {
        val sent = mutableMapOf<String, String>()
        var connected = false

        override fun setRequestProperty(key: String, value: String) {
            sent[key] = value
        }

        override fun connect() {
            connected = true
        }

        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String): String? = if (name.equals("Location", true)) location else null
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
    }

    private class Server(private val routes: Map<String, Pair<Int, String?>>) {
        val opened = mutableListOf<FakeConnection>()

        fun open(url: URL): HttpURLConnection {
            val (status, location) = routes[url.toString()] ?: error("Unexpected request to $url")
            return FakeConnection(url, status, location).also { opened += it }
        }
    }

    @Test
    fun `a valid https download returns the connection and keeps resume and auth headers`() {
        val server = Server(mapOf("https://huggingface.co/model.litertlm" to (206 to null)))

        val connection = openHttpsDownload(
            "https://huggingface.co/model.litertlm",
            mapOf("Authorization" to "Bearer token", "Range" to "bytes=10-"),
            server::open,
        )

        assertSame(server.opened.single(), connection)
        assertEquals(206, connection.responseCode)
        assertEquals("Bearer token", server.opened.single().sent["Authorization"])
        assertEquals("bytes=10-", server.opened.single().sent["Range"])
        assertFalse(connection.instanceFollowRedirects, "redirects are checked hop by hop")
    }

    @Test
    fun `a plain http download url is refused before any connection`() {
        val server = Server(emptyMap())

        assertThrows(InsecureDownloadUrlException::class.java) {
            openHttpsDownload("http://example.com/voice.tar.bz2", open = server::open)
        }
        assertTrue(server.opened.isEmpty())
    }

    @Test
    fun `an https to http redirect is refused and the http target is never contacted`() {
        val server = Server(
            mapOf("https://huggingface.co/model.litertlm" to (302 to "http://cdn.example.com/model.litertlm")),
        )

        assertThrows(InsecureDownloadUrlException::class.java) {
            openHttpsDownload("https://huggingface.co/model.litertlm", mapOf("Range" to "bytes=10-"), server::open)
        }
        assertEquals(listOf("https://huggingface.co/model.litertlm"), server.opened.map { it.url.toString() })
    }

    @Test
    fun `an https cdn redirect is followed with resume kept and credentials dropped`() {
        val server = Server(
            mapOf(
                "https://huggingface.co/model.litertlm" to (302 to "https://cdn-lfs.hf.co/signed/model?x=1"),
                "https://cdn-lfs.hf.co/signed/model?x=1" to (206 to null),
            ),
        )

        val connection = openHttpsDownload(
            "https://huggingface.co/model.litertlm",
            mapOf("Authorization" to "Bearer token", "Range" to "bytes=10-"),
            server::open,
        )

        val cdn = server.opened.last()
        assertSame(cdn, connection)
        assertEquals("bytes=10-", cdn.sent["Range"])
        assertNull(cdn.sent["Authorization"], "a bearer token must not follow a redirect to another host")
        assertEquals("Bearer token", server.opened.first().sent["Authorization"])
    }

    @Test
    fun `a relative same-host redirect is followed over https`() {
        val server = Server(
            mapOf(
                "https://github.com/releases/voice.tar.bz2" to (301 to "/releases/v2/voice.tar.bz2"),
                "https://github.com/releases/v2/voice.tar.bz2" to (200 to null),
            ),
        )

        val connection = openHttpsDownload("https://github.com/releases/voice.tar.bz2", open = server::open)

        assertEquals("https://github.com/releases/v2/voice.tar.bz2", connection.url.toString())
    }
}
