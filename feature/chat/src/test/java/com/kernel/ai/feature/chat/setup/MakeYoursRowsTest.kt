package com.kernel.ai.feature.chat.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure row-state mapping for the Make Jandal yours step (#1855 part 3). */
class MakeYoursRowsTest {

    private val ready = MakeYoursPlatform(
        hasMicrophone = true,
        micGranted = true,
        assistantRoleAvailable = true,
        assistantRoleHeld = true,
        notificationsGranted = true,
        exactAlarmAllowed = true,
    )

    @Test
    fun `hey Jandal is not available without a microphone, whatever is stored`() {
        val noMic = ready.copy(hasMicrophone = false)
        assertEquals(MakeYoursStatus.NotAvailable, MakeYoursRows.wakeWord(storedEnabled = true, platform = noMic))
        assertEquals(MakeYoursStatus.NotAvailable, MakeYoursRows.wakeWord(storedEnabled = false, platform = noMic))
    }

    @Test
    fun `hey Jandal is not available when the phone has no assistant role`() {
        val noRole = ready.copy(assistantRoleAvailable = false, assistantRoleHeld = false)
        assertEquals(MakeYoursStatus.NotAvailable, MakeYoursRows.wakeWord(storedEnabled = true, platform = noRole))
    }

    @Test
    fun `hey Jandal shows On or Off from the stored choice`() {
        assertEquals(MakeYoursStatus.On, MakeYoursRows.wakeWord(storedEnabled = true, platform = ready))
        assertEquals(MakeYoursStatus.Off, MakeYoursRows.wakeWord(storedEnabled = false, platform = ready))
    }

    @Test
    fun `default assistant is On only when Jandal holds the role`() {
        assertEquals(MakeYoursStatus.On, MakeYoursRows.defaultAssistant(ready))
        assertEquals(
            MakeYoursStatus.Off,
            MakeYoursRows.defaultAssistant(ready.copy(assistantRoleHeld = false)),
        )
    }

    @Test
    fun `default assistant is Not available when the phone has no assistant role`() {
        assertEquals(
            MakeYoursStatus.NotAvailable,
            MakeYoursRows.defaultAssistant(ready.copy(assistantRoleAvailable = false, assistantRoleHeld = false)),
        )
    }

    @Test
    fun `notifications and exact alarms follow the platform grant`() {
        assertEquals(MakeYoursStatus.On, MakeYoursRows.notifications(ready))
        assertEquals(MakeYoursStatus.Off, MakeYoursRows.notifications(ready.copy(notificationsGranted = false)))
        assertEquals(MakeYoursStatus.On, MakeYoursRows.exactAlarm(ready))
        assertEquals(MakeYoursStatus.Off, MakeYoursRows.exactAlarm(ready.copy(exactAlarmAllowed = false)))
    }

    @Test
    fun `wake word block reason names the first missing prerequisite`() {
        assertTrue(MakeYoursRows.wakeWordBlockReason(ready.copy(hasMicrophone = false))!!.contains("no microphone"))
        assertTrue(MakeYoursRows.wakeWordBlockReason(ready.copy(micGranted = false))!!.contains("microphone"))
        assertTrue(MakeYoursRows.wakeWordBlockReason(ready.copy(assistantRoleHeld = false))!!.contains("default assistant"))
        assertNull(MakeYoursRows.wakeWordBlockReason(ready))
    }

    @Test
    fun `status labels are plain English`() {
        assertEquals("On", MakeYoursRows.statusLabel(MakeYoursStatus.On))
        assertEquals("Off", MakeYoursRows.statusLabel(MakeYoursStatus.Off))
        assertEquals("Not available", MakeYoursRows.statusLabel(MakeYoursStatus.NotAvailable))
    }
}
