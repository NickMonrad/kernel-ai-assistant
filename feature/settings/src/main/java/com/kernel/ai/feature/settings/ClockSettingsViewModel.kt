package com.kernel.ai.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernel.ai.core.memory.clock.ClockAlertConfig
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockSoundConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ClockSettingsViewModel @Inject constructor(
    private val clockRepository: ClockRepository,
) : ViewModel() {
    val alertConfig: StateFlow<ClockAlertConfig> =
        clockRepository.observeClockAlertConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClockAlertConfig())

    val soundConfig: StateFlow<ClockSoundConfig> =
        clockRepository.observeClockSoundConfig()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClockSoundConfig())

    fun setTimerAutoStopDurationMs(value: Long) {
        viewModelScope.launch { clockRepository.setTimerAutoStopDurationMs(value) }
    }

    fun setAlarmRingDurationMs(value: Long) {
        viewModelScope.launch { clockRepository.setAlarmRingDurationMs(value) }
    }

    fun setSnoozeDurationMs(value: Long) {
        viewModelScope.launch { clockRepository.setSnoozeDurationMs(value) }
    }

    fun setMaxAutoSnoozes(value: Int) {
        viewModelScope.launch { clockRepository.setMaxAutoSnoozes(value) }
    }

    fun setDefaultAlarmSoundUri(uri: String?) {
        viewModelScope.launch { clockRepository.setDefaultAlarmSoundUri(uri) }
    }

    fun setTimerSoundUri(uri: String?) {
        viewModelScope.launch { clockRepository.setTimerSoundUri(uri) }
    }
}
