package com.kernel.ai.feature.chat.setup

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kernel.ai.core.inference.download.DownloadNetworkChoice
import com.kernel.ai.core.inference.setup.SetupStep
import com.kernel.ai.core.voice.VoiceInputEngine
import com.kernel.ai.core.voice.VoiceOutputEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Returning from a system page refreshes the Make Jandal yours rows on ON_RESUME (#1855 part 3). The refresh
 * must not run any row action: the only way a row changes is a tap.
 */
class MakeYoursResumeRefreshTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resumeRefreshesRowsOnceEachTimeAndTapsNothing() {
        val owner = TestLifecycleOwner()
        var refreshes = 0
        val taps = mutableListOf<String>()

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SetupWizardContent(
                    state = SetupWizardUiState(
                        loaded = true,
                        step = SetupStep.MAKE_JANDAL_YOURS,
                        inputOptions = listOf(VoiceInputEngine.Auto),
                        inputEngine = VoiceInputEngine.Auto,
                        outputOptions = listOf(VoiceOutputEngine.AndroidTts),
                        outputEngine = VoiceOutputEngine.AndroidTts,
                        choice = DownloadNetworkChoice.WIFI_ONLY,
                        choiceRecorded = true,
                    ),
                    onInputSelected = {},
                    onOutputSelected = {},
                    onChoiceSelected = {},
                    makeYours = MakeYoursActions(
                        onWakeWordChoiceChanged = { taps += "wake" },
                        onAllowMicrophone = { taps += "microphone" },
                        onAllowNotifications = { taps += "notifications" },
                        onOpenDefaultAssistant = { taps += "assistant" },
                        onOpenUsageAccess = { taps += "usage" },
                        onOpenExactAlarm = { taps += "exact" },
                    ),
                    onPrimary = {},
                    onBack = {},
                    onSkip = {},
                )
                RefreshOnResume(onResume = { refreshes++ })
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread { owner.set(Lifecycle.State.RESUMED) }
        composeRule.waitForIdle()
        assertEquals(1, refreshes)

        composeRule.runOnUiThread { owner.set(Lifecycle.State.CREATED) }
        composeRule.runOnUiThread { owner.set(Lifecycle.State.RESUMED) }
        composeRule.waitForIdle()
        assertEquals(2, refreshes)

        assertEquals(emptyList<String>(), taps)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun set(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
