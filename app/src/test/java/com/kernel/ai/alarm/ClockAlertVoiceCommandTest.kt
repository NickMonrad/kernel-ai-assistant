package com.kernel.ai.alarm

import com.kernel.ai.core.memory.clock.ClockEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ClockAlertVoiceCommandTest {
    @Test
    fun `parseClockAlertVoiceCommand matches supported alert phrases`() {
        assertEquals(ClockAlertVoiceCommand.DISMISS, parseClockAlertVoiceCommand("stop"))
        assertEquals(ClockAlertVoiceCommand.DISMISS, parseClockAlertVoiceCommand("dismiss timer"))
        assertEquals(ClockAlertVoiceCommand.SNOOZE, parseClockAlertVoiceCommand("snooze alarm"))
        assertEquals(ClockAlertVoiceCommand.ADD_ONE_MINUTE, parseClockAlertVoiceCommand("add one minute"))
        assertEquals(ClockAlertVoiceCommand.ADD_ONE_MINUTE, parseClockAlertVoiceCommand("another minute"))
    }

    @Test
    fun `parseClockAlertVoiceCommand rejects unsupported phrases`() {
        assertNull(parseClockAlertVoiceCommand("pause stopwatch"))
        assertNull(parseClockAlertVoiceCommand("what time is it"))
    }

    @Test
    fun `shouldAutoStartAlertVoiceControl only enables ringing voice capture for alarms and timers`() {
        assertEquals(true, shouldAutoStartAlertVoiceControl(true, ClockEventType.ALARM))
        assertEquals(true, shouldAutoStartAlertVoiceControl(true, ClockEventType.TIMER))
        assertEquals(false, shouldAutoStartAlertVoiceControl(false, ClockEventType.ALARM))
        assertEquals(false, shouldAutoStartAlertVoiceControl(true, ClockEventType.PRE_ALARM))
    }


    @Test
    fun `alertVoiceUnsupportedMessage is truthful for timer snooze`() {
        assertEquals(
            "Snooze is only available for alarms.",
            alertVoiceUnsupportedMessage(ClockAlertVoiceCommand.SNOOZE, ClockEventType.TIMER),
        )
        assertNull(alertVoiceUnsupportedMessage(ClockAlertVoiceCommand.DISMISS, ClockEventType.TIMER))
    }
}
