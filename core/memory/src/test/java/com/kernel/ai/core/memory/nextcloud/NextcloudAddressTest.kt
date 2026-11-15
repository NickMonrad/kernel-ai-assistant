package com.kernel.ai.core.memory.nextcloud

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NextcloudAddressTest {
    @Test
    fun `a bare host is normalized to https`() {
        assertEquals(accepted("https://cloud.example.com"), NextcloudAddress.normalize("cloud.example.com"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(accepted("https://cloud.example.com"), NextcloudAddress.normalize("  cloud.example.com  "))
    }

    @Test
    fun `a base path is preserved without a trailing slash`() {
        assertEquals(
            accepted("https://cloud.example.com/nextcloud"),
            NextcloudAddress.normalize("cloud.example.com/nextcloud/"),
        )
    }

    @Test
    fun `an explicit port is preserved`() {
        assertEquals(
            accepted("https://cloud.example.com:8443/nextcloud"),
            NextcloudAddress.normalize("cloud.example.com:8443/nextcloud"),
        )
    }

    @Test
    fun `an explicit https url keeps its scheme`() {
        assertEquals(
            accepted("https://cloud.example.com/nextcloud"),
            NextcloudAddress.normalize("https://cloud.example.com/nextcloud"),
        )
    }

    @Test
    fun `the host is lowercased while the path keeps its case`() {
        assertEquals(
            accepted("https://cloud.example.com/NextCloud"),
            NextcloudAddress.normalize("CLOUD.Example.COM/NextCloud"),
        )
    }

    @Test
    fun `an ipv6 literal keeps its brackets and port`() {
        assertEquals(
            accepted("https://[2001:db8::1]:8443/dav"),
            NextcloudAddress.normalize("[2001:db8::1]:8443/dav"),
        )
    }

    @Test
    fun `explicit http is rejected and never rewritten to https`() {
        for (address in listOf("http://cloud.example.com", "HTTP://cloud.example.com:8080/nextcloud")) {
            val rejected = NextcloudAddress.normalize(address) as NextcloudAddressResult.Rejected
            assertEquals(NextcloudAddressResult.Rejected.Code.INSECURE_SCHEME, rejected.code, address)
            assertTrue(rejected.message.contains("requires HTTPS"), address)
        }
    }

    @Test
    fun `isHttps accepts only absolute https urls`() {
        assertTrue(NextcloudAddress.isHttps("https://cloud.example.com/remote.php/dav"))
        assertTrue(NextcloudAddress.isHttps("HTTPS://cloud.example.com"))
        assertFalse(NextcloudAddress.isHttps("http://cloud.example.com"))
        assertFalse(NextcloudAddress.isHttps("cloud.example.com"))
        assertFalse(NextcloudAddress.isHttps("not a url"))
    }

    @Test
    fun `an unsupported scheme is rejected rather than rewritten`() {
        val result = NextcloudAddress.normalize("ftp://cloud.example.com")

        assertEquals(
            NextcloudAddressResult.Rejected.Code.UNSUPPORTED_SCHEME,
            (result as NextcloudAddressResult.Rejected).code,
        )
    }

    @Test
    fun `a blank address is rejected`() {
        assertEquals(
            NextcloudAddressResult.Rejected.Code.BLANK,
            (NextcloudAddress.normalize("   ") as NextcloudAddressResult.Rejected).code,
        )
    }

    @Test
    fun `embedded credentials and malformed hosts are rejected`() {
        assertEquals(
            NextcloudAddressResult.Rejected.Code.MALFORMED,
            (NextcloudAddress.normalize("alice:secret@cloud.example.com") as NextcloudAddressResult.Rejected).code,
        )
        assertEquals(
            NextcloudAddressResult.Rejected.Code.MALFORMED,
            (NextcloudAddress.normalize("cloud example.com") as NextcloudAddressResult.Rejected).code,
        )
        assertEquals(
            NextcloudAddressResult.Rejected.Code.MALFORMED,
            (NextcloudAddress.normalize("cloud.example.com:not-a-port") as NextcloudAddressResult.Rejected).code,
        )
        assertEquals(
            NextcloudAddressResult.Rejected.Code.MALFORMED,
            (NextcloudAddress.normalize("cloud.example.com:70000") as NextcloudAddressResult.Rejected).code,
        )
        assertEquals(
            NextcloudAddressResult.Rejected.Code.MALFORMED,
            (NextcloudAddress.normalize("https://") as NextcloudAddressResult.Rejected).code,
        )
    }

    private fun accepted(url: String) = NextcloudAddressResult.Accepted(url)
}
