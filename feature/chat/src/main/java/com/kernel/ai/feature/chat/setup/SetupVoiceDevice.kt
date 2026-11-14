package com.kernel.ai.feature.chat.setup

import android.content.Context
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.VoiceCapabilities
import com.kernel.ai.core.voice.readHasSystemTtsEngine
import com.kernel.ai.core.voice.readVoiceCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device facts the speech step of the setup wizard depends on (#1855 part 2). A wrapper so the view model
 * can be tested with faked availability.
 */
@Singleton
class SetupVoiceDevice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hardwareProfileDetector: HardwareProfileDetector,
) {
    fun speechInputCapabilities(): VoiceCapabilities = readVoiceCapabilities(context)

    fun hasSystemTtsEngine(): Boolean = readHasSystemTtsEngine(context)

    fun hardwareTier(): HardwareTier = hardwareProfileDetector.profile.tier
}
