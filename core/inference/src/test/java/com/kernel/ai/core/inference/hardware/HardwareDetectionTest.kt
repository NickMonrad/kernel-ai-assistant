package com.kernel.ai.core.inference.hardware

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for hardware tier classification and Mali GPU detection.
 *
 * Pure-logic unit tests — no Android dependencies required.
 *
 * safeTokenCount() is not tested here because it calls android.util.Log
 * which requires Robolectric or Android runtime. It is tested indirectly
 * through the HardwareProfileDetector integration path.
 */
class HardwareDetectionTest {

    // -----------------------------------------------------------------------
    // HardwareTier.fromRamBytes
    // -----------------------------------------------------------------------

    @Test
    fun `FLAGSHIP tier for 12 GB RAM`() {
        assertEquals(HardwareTier.FLAGSHIP, HardwareTier.fromRamBytes(12L * 1024 * 1024 * 1024))
    }

    @Test
    fun `FLAGSHIP tier for exactly 10 GB RAM`() {
        assertEquals(HardwareTier.FLAGSHIP, HardwareTier.fromRamBytes(10L * 1024 * 1024 * 1024))
    }

    @Test
    fun `MID_RANGE tier for 8 GB RAM`() {
        assertEquals(HardwareTier.MID_RANGE, HardwareTier.fromRamBytes(8L * 1024 * 1024 * 1024))
    }

    @Test
    fun `MID_RANGE tier for exactly 6 GB RAM`() {
        assertEquals(HardwareTier.MID_RANGE, HardwareTier.fromRamBytes(6L * 1024 * 1024 * 1024))
    }

    @Test
    fun `MID_RANGE tier for 7 GB RAM`() {
        assertEquals(HardwareTier.MID_RANGE, HardwareTier.fromRamBytes(7L * 1024 * 1024 * 1024))
    }

    @Test
    fun `LOW_POWER tier for 4 GB RAM`() {
        assertEquals(HardwareTier.LOW_POWER, HardwareTier.fromRamBytes(4L * 1024 * 1024 * 1024))
    }

    @Test
    fun `LOW_POWER tier for 2 GB RAM`() {
        assertEquals(HardwareTier.LOW_POWER, HardwareTier.fromRamBytes(2L * 1024 * 1024 * 1024))
    }

    // -----------------------------------------------------------------------
    // isMaliGpuSoc
    // -----------------------------------------------------------------------

    // --- Samsung Exynos (MID_RANGE/LOW_POWER) — SKIP GPU ---

    @Test
    fun `Samsung Exynos 2100 on MID_RANGE skips GPU`() {
        assertTrue(isMaliGpuSoc("Samsung", "exynos2100", HardwareTier.MID_RANGE))
    }

    @Test
    fun `Samsung Exynos 2200 on MID_RANGE skips GPU`() {
        assertTrue(isMaliGpuSoc("SAMSUNG", "EXYNOS2200", HardwareTier.MID_RANGE))
    }

    @Test
    fun `Samsung S5E model on MID_RANGE skips GPU`() {
        assertTrue(isMaliGpuSoc("Samsung", "S5E9845", HardwareTier.MID_RANGE))
    }

    @Test
    fun `Samsung Exynos on LOW_POWER skips GPU`() {
        assertTrue(isMaliGpuSoc("samsung", "Exynos 850", HardwareTier.LOW_POWER))
    }

    // --- Samsung Exynos (FLAGSHIP) — do NOT skip ---

    @Test
    fun `Samsung Exynos 2400 on FLAGSHIP does NOT skip GPU`() {
        // S24 Ultra has Xclipse GPU (AMD RDNA 3) — stable OpenCL driver
        assertFalse(isMaliGpuSoc("Samsung", "exynos2400", HardwareTier.FLAGSHIP))
    }

    // --- Qualcomm Snapdragon — do NOT skip ---

    @Test
    fun `Qualcomm Snapdragon 888 on MID_RANGE does NOT skip GPU`() {
        assertFalse(isMaliGpuSoc("Qualcomm", "SM8350", HardwareTier.MID_RANGE))
    }

    @Test
    fun `Qualcomm Snapdragon 8 Gen 2 on MID_RANGE does NOT skip GPU`() {
        assertFalse(isMaliGpuSoc("Qualcomm", "SM8550", HardwareTier.MID_RANGE))
    }

    @Test
    fun `Qualcomm on LOW_POWER does NOT skip GPU`() {
        // Low power still tries GPU since Adreno drivers are stable
        assertFalse(isMaliGpuSoc("Qualcomm", "SM4350", HardwareTier.LOW_POWER))
    }

    // --- MediaTek — SKIP GPU ---

    @Test
    fun `MediaTek Dimensity on MID_RANGE skips GPU`() {
        assertTrue(isMaliGpuSoc("MediaTek", "MT6893", HardwareTier.MID_RANGE))
    }

    @Test
    fun `MediaTek on LOW_POWER skips GPU`() {
        assertTrue(isMaliGpuSoc("MEDIATEK", "mt6765", HardwareTier.LOW_POWER))
    }

    @Test
    fun `MediaTek on FLAGSHIP does NOT skip GPU`() {
        // Dimensity 9300+ has enough headroom that GPU is safer
        assertFalse(isMaliGpuSoc("MediaTek", "MT6989", HardwareTier.FLAGSHIP))
    }

    // --- HiSilicon — SKIP GPU ---

    @Test
    fun `HiSilicon Kirin on MID_RANGE skips GPU`() {
        assertTrue(isMaliGpuSoc("HiSilicon", "Kirin990", HardwareTier.MID_RANGE))
    }

    // --- Unknown / empty SoC ---

    @Test
    fun `unknown SoC manufacturer on MID_RANGE does NOT skip GPU`() {
        assertFalse(isMaliGpuSoc("UnknownVendor", "UnknownModel", HardwareTier.MID_RANGE))
    }

    @Test
    fun `empty SoC strings on MID_RANGE does NOT skip GPU`() {
        assertFalse(isMaliGpuSoc("", "", HardwareTier.MID_RANGE))
    }
}
