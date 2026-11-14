package com.kernel.ai.feature.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportEmailTest {

    private val buildInfo = DiagnosticBuildInfo(
        versionName = "1.0.4",
        versionCode = 4,
        buildType = "release",
        gitSha = "3340e57b",
        buildTimestamp = "2026-11-14T09:00:00Z",
    )
    private val device = SupportDeviceInfo("samsung", "SM-S918B", "15", 35)

    @Test
    fun `subject names the app version and code`() {
        assertEquals("Jandal AI v1.0.4 (4) feedback", supportEmailSubject(buildInfo))
    }

    @Test
    fun `body lists device and build before the description`() {
        val body = supportEmailBody(buildInfo, device, "  The alarm didn't ring.\n", includeUserContent = false)

        assertEquals(
            """
            Phone: samsung SM-S918B
            Android: 15 (API 35)
            App: v1.0.4 (4) release
            Commit: 3340e57b
            Built: 2026-11-14T09:00:00Z
            Chats and messages in attached log: redacted (pattern-based; may be incomplete)

            What happened:
            The alarm didn't ring.

            """.trimIndent(),
            body,
        )
    }

    @Test
    fun `body notes opt-in and placeholder for an empty description`() {
        val body = supportEmailBody(buildInfo, SupportDeviceInfo("", "", "", 35), " ", includeUserContent = true)

        assertTrue(body.contains("Phone: unknown"))
        assertTrue(body.contains("Android: unknown (API 35)"))
        assertTrue(body.contains("Chats and messages in attached log: included"))
        assertTrue(body.endsWith("What happened:\n(not described)\n"))
    }

    @Test
    fun `support address is the dedicated support inbox`() {
        assertEquals("jandal.ai.support@gmail.com", SUPPORT_EMAIL_ADDRESS)
    }
}
