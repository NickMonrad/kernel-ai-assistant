package com.kernel.ai.core.voice.kokoro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class KokoroSpeedCheckCacheTest {
    private val baseKey = kokoroSpeedCheckKey(versionCode = 42L, packVersion = "pack-a", maxMedianRtf = 0.70)

    @Test
    fun `the same app build, pack and threshold give the same key`() {
        assertEquals(baseKey, kokoroSpeedCheckKey(versionCode = 42L, packVersion = "pack-a", maxMedianRtf = 0.70))
    }

    @Test
    fun `a new app versionCode changes the key so the check runs again`() {
        assertNotEquals(baseKey, kokoroSpeedCheckKey(versionCode = 43L, packVersion = "pack-a", maxMedianRtf = 0.70))
    }

    @Test
    fun `a new Kokoro pack version changes the key so the check runs again`() {
        assertNotEquals(baseKey, kokoroSpeedCheckKey(versionCode = 42L, packVersion = "pack-b", maxMedianRtf = 0.70))
    }

    @Test
    fun `a new speed threshold changes the key so the check runs again`() {
        assertNotEquals(baseKey, kokoroSpeedCheckKey(versionCode = 42L, packVersion = "pack-a", maxMedianRtf = 0.65))
    }
}
