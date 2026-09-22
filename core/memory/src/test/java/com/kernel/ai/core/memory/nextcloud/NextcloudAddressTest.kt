package com.kernel.ai.core.memory.nextcloud

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `explicit http is rejected while insecure http is disabled`() {
        val result = NextcloudAddress.normalize("http://cloud.example.com", allowInsecureHttp = false)

        val rejected = result as NextcloudAddressResult.Rejected
        assertEquals(NextcloudAddressResult.Rejected.Code.INSECURE_SCHEME, rejected.code)
        assertTrue(rejected.message.contains("Plain HTTP is disabled"))
    }

    @Test
    fun `explicit http is accepted once insecure http is enabled`() {
        assertEquals(
            accepted("http://cloud.example.com"),
            NextcloudAddress.normalize("http://cloud.example.com", allowInsecureHttp = true),
        )
    }

    @Test
    fun `enabling insecure http does not change the https default`() {
        assertEquals(
            accepted("https://cloud.example.com"),
            NextcloudAddress.normalize("cloud.example.com", allowInsecureHttp = true),
        )
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
