package com.kernel.ai.core.voice

import android.app.AppOpsManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * #1502: the workaround must stay scoped to the device where MagicOS microphone arbitration was
 * physically proven, and the Usage Access prerequisite must reflect the *actual* grant.
 */
class HonorCameraCoexistenceTest {

    @Test
    fun `Honor BKQ-N49 is the affected device`() {
        assertTrue(HonorCameraCoexistence.isAffectedDevice("HONOR", "BKQ-N49"))
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            // other Honor models are not covered by the physical evidence
            "'HONOR','ANY-NX1'",
            "'HONOR','BKQ-N50'",
            "'HONOR','ELP-NX9'",
            // the reference and tracked foreground-input devices keep the existing wake path
            "'samsung','SM-S918B'",
            "'samsung','SM-G991B'",
            "'Google','sdk_gphone64_x86_64'",
            // partial matches must not qualify
            "'',''",
            "'HONOR',''",
            "'','BKQ-N49'",
        ],
    )
    fun `other devices are unaffected`(manufacturer: String, model: String) {
        assertFalse(HonorCameraCoexistence.isAffectedDevice(manufacturer, model))
    }

    @Test
    fun `device match tolerates case and surrounding whitespace`() {
        assertTrue(HonorCameraCoexistence.isAffectedDevice(" honor ", " bkq-n49 "))
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            // mode, expected
            "0, true", // MODE_ALLOWED
            "1, false", // MODE_IGNORED
            "3, false", // MODE_DEFAULT — declaring the permission grants nothing
            "4, false", // MODE_FOREGROUND — not the grant this workaround needs
        ],
    )
    fun `usage access is granted only for MODE_ALLOWED`(mode: Int, expected: Boolean) {
        val appOps = mockk<AppOpsManager>()
        every { appOps.unsafeCheckOpNoThrow(any(), any(), any()) } returns mode
        val context = mockk<Context>()
        every { context.getSystemService(Context.APP_OPS_SERVICE) } returns appOps
        every { context.packageName } returns "com.kernel.ai.debug"

        assertTrue(HonorCameraCoexistence.hasUsageAccess(context) == expected)
    }

    @Test
    fun `missing usage stats service means no access`() {
        val context = mockk<Context>()
        every { context.getSystemService(Context.APP_OPS_SERVICE) } returns null

        assertFalse(HonorCameraCoexistence.hasUsageAccess(context))
    }

    // ── Re-arm guard: capture is withheld while the camera may own the foreground ─────────────

    private fun currentPackage(packageName: String?) = ForegroundPackageSource { packageName }

    @Test
    fun `a camera already in the foreground withholds wake capture`() {
        assertTrue(
            mustWithholdWakeCapture(
                currentPackage(HonorCameraCoexistence.CAMERA_PACKAGE),
                HonorCameraCoexistence.CAMERA_PACKAGE,
            ),
        )
    }

    @Test
    fun `another app in the foreground does not withhold wake capture`() {
        assertFalse(
            mustWithholdWakeCapture(currentPackage("com.example.other"), HonorCameraCoexistence.CAMERA_PACKAGE),
        )
    }

    @Test
    fun `an unreadable foreground state withholds wake capture`() {
        assertTrue(
            mustWithholdWakeCapture(currentPackage(null), HonorCameraCoexistence.CAMERA_PACKAGE),
            "an unknown state must not risk blocking the camera",
        )
    }

    @Test
    fun `a failing foreground query withholds wake capture`() {
        val throwing = ForegroundPackageSource { throw SecurityException("usage access lost") }

        assertTrue(
            mustWithholdWakeCapture(throwing, HonorCameraCoexistence.CAMERA_PACKAGE),
            "a query that cannot answer must not risk blocking the camera",
        )
    }

    // ── Current-foreground resolution: a long camera session must still be resolvable ─────────

    @Test
    fun `the most recently visible package is the one in front`() {
        assertEquals(
            HonorCameraCoexistence.CAMERA_PACKAGE,
            mostRecentlyVisiblePackage(
                listOf(
                    "com.hihonor.android.launcher" to 1_700_000_000_000L,
                    HonorCameraCoexistence.CAMERA_PACKAGE to 1_700_000_600_000L,
                ),
            ),
            "a camera in front since long before any window must still resolve as the current app",
        )
    }

    @Test
    fun `a package that took over after the camera is the one in front`() {
        assertEquals(
            "com.hihonor.android.launcher",
            mostRecentlyVisiblePackage(
                listOf(
                    HonorCameraCoexistence.CAMERA_PACKAGE to 1_700_000_600_000L,
                    "com.hihonor.android.launcher" to 1_700_000_900_000L,
                ),
            ),
        )
    }

    @Test
    fun `no visible timestamp means the state cannot be resolved`() {
        assertNull(mostRecentlyVisiblePackage(listOf("com.example.other" to 0L)))
    }
}
