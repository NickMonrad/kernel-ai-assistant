package com.kernel.ai.core.voice.kokoro

import android.content.Context
import com.kernel.ai.core.voice.VoiceOutputPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Remembers a passing Kokoro speed check (#2174). A pass is reused only while the app versionCode, the Kokoro pack
 * version and the select threshold all match the ones it was taken with. A fail, or a check that could not run,
 * clears it, so the next selection checks again. The reply-time memory gate is separate and runs on every load.
 */
@Singleton
class KokoroSpeedCheckCache @Inject constructor(
    private val voiceOutputPreferences: VoiceOutputPreferences,
    @ApplicationContext private val context: Context,
) {
    /** True when the last check passed on this app build, this pack and this threshold. */
    suspend fun hasValidPass(): Boolean = voiceOutputPreferences.kokoroSpeedPass.first() == currentKey()

    suspend fun recordPass() {
        voiceOutputPreferences.setKokoroSpeedPass(currentKey())
    }

    /** Clears any pass: the last check failed or could not run, so the next selection checks again. */
    suspend fun recordNotPassed() {
        voiceOutputPreferences.setKokoroSpeedPass(null)
    }

    private fun currentKey(): String = kokoroSpeedCheckKey(
        versionCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode,
        packVersion = KokoroSetup.packVersion(),
        maxMedianRtf = KOKORO_SELECT_MAX_MEDIAN_RTF,
    )
}

/** The key a pass is stored under. Changing any part invalidates the stored pass. */
internal fun kokoroSpeedCheckKey(versionCode: Long, packVersion: String, maxMedianRtf: Double): String =
    "versionCode=$versionCode;pack=$packVersion;maxMedianRtf=$maxMedianRtf"
