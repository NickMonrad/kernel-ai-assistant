package com.kernel.ai.feature.chat

import androidx.lifecycle.ViewModel
import com.kernel.ai.core.voice.kokoro.KokoroAutoCheck
import com.kernel.ai.core.voice.kokoro.KokoroAutoCheckResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Carries the Kokoro speed-check result from setup to the chat, which shows it once as a snackbar (#2173). */
@HiltViewModel
class KokoroCheckNoticeViewModel @Inject constructor(
    private val kokoroAutoCheck: KokoroAutoCheck,
) : ViewModel() {
    val result: StateFlow<KokoroAutoCheckResult?> = kokoroAutoCheck.result

    /** Clears the result once it is shown, so the next chat screen does not repeat it. */
    fun consume() {
        kokoroAutoCheck.takeResult()
    }
}
