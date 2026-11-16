package com.kernel.ai.feature.chat.setup

import android.content.Context
import android.os.Build
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.AndroidNativeRecognitionSupport
import com.kernel.ai.core.voice.systemTtsEngineInstalled
import com.kernel.ai.core.voice.InflectMicroModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Device facts the speech step of the setup wizard depends on (#1855 part 2). A wrapper so the view model
 * can be tested with faked availability. Failed probes read as "not available", never as a crash.
 */
@Singleton
class SetupVoiceDevice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val androidNativeRecognitionSupport: AndroidNativeRecognitionSupport,
    private val hardwareProfileDetector: HardwareProfileDetector,
) {
    /** True when Android's own recognizer can run here: no blocking reason for service, on-device, or language. */
    suspend fun androidNativeInputReady(): Boolean = try {
        androidNativeRecognitionSupport.getAvailability().blockingReason == null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    fun hasSystemTtsEngine(): Boolean = try {
        systemTtsEngineInstalled(context)
    } catch (e: Exception) {
        false
    }

    fun hardwareTier(): HardwareTier = hardwareProfileDetector.profile.tier

    /** True when Inflect Micro is release-eligible here (tier and ABI). Failed probes read as not eligible. */
    fun inflectEligible(): Boolean = try {
        InflectMicroModelSpec.isReleaseEligible(hardwareTier(), Build.SUPPORTED_ABIS)
    } catch (e: Exception) {
        false
    }
}
