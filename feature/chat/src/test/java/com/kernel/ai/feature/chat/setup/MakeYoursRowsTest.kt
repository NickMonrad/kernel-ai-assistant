package com.kernel.ai.feature.chat.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Pure row-state mapping for the Make Jandal yours step, apart from Hey Jandal (see [HeyJandalCardTest]). */
class MakeYoursRowsTest {

    private val ready = MakeYoursPlatform(
        hasMicrophone = true,
        micGranted = true,
        wakeModelAvailable = true,
        usageAccessRequired = false,
        assistantRoleAvailable = true,
        assistantRoleHeld = true,
        notificationsGranted = true,
        exactAlarmAllowed = true,
    )

    @Test
    fun `default assistant is On when Jandal holds the role and Off when it does not`() {
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
}
