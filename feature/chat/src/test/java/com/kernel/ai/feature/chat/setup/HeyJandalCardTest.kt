package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.ui.ChipStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * One test per row of the Hey Jandal state table (docs/specs/setup-visual-pass/README.md, step 4; compose-notes §4.4).
 * Inputs: [stored] is the saved wake-word value, [choice] is what Finish will apply.
 */
class HeyJandalCardTest {

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

    private fun card(
        stored: Boolean,
        choice: Boolean,
        platform: MakeYoursPlatform = ready,
        extraBytes: Long = 0L,
    ) = MakeYoursRows.heyJandalCard(
        stored = stored,
        choice = choice,
        platform = platform,
        extraBytes = extraBytes,
    )

    @Test
    fun `not stored and not chosen is Off with Turn on and Why`() {
        val c = card(stored = false, choice = false)
        assertEquals(ChipStatus.Off, c.status)
        assertEquals(HeyJandalAction.TurnOn, c.primary)
        assertNull(c.secondary)
        assertEquals("Uses a little battery.", c.caveat)
        assertEquals("Say \"Hey Jandal\" to start talking, hands-free.", c.description)
        assertNotNull(c.why)
        assertTrue(c.why!!.contains("Whisper tiny.en verifies the wake phrase."))
    }

    @Test
    fun `not stored and chosen is OnAtFinish with Turn off and no Why`() {
        val c = card(stored = false, choice = true)
        assertEquals(ChipStatus.OnAtFinish, c.status)
        assertNull(c.primary)
        assertEquals(HeyJandalAction.TurnOff, c.secondary)
        assertTrue(c.description.endsWith("Turns on when you tap Finish."))
        assertNull(c.why)
    }

    @Test
    fun `stored and chosen is On with Turn off and no pending text`() {
        val c = card(stored = true, choice = true)
        assertEquals(ChipStatus.On, c.status)
        assertNull(c.primary)
        assertEquals(HeyJandalAction.TurnOff, c.secondary)
        assertFalse(c.description.contains("Finish"))
        assertNull(c.why)
    }

    @Test
    fun `stored and not chosen is Off with Turn on and the turns-off-at-Finish text`() {
        val c = card(stored = true, choice = false)
        assertEquals(ChipStatus.Off, c.status)
        assertEquals(HeyJandalAction.TurnOn, c.primary)
        assertNull(c.secondary)
        assertTrue(c.description.endsWith("Turns off when you tap Finish."))
        assertNotNull(c.why)
    }

    @Test
    fun `no microphone is NotAvailable with no button and no caveat when not chosen`() {
        val c = card(stored = false, choice = false, platform = ready.copy(hasMicrophone = false))
        assertEquals(ChipStatus.NotAvailable, c.status)
        assertNull(c.primary)
        assertNull(c.secondary)
        assertNull(c.caveat)
        assertEquals("This phone has no microphone, so Hey Jandal can't listen.", c.description)
        assertNotNull(c.why)
    }

    @Test
    fun `no microphone while chosen is NotAvailable, stays off, and offers Turn off`() {
        val c = card(stored = true, choice = true, platform = ready.copy(hasMicrophone = false))
        assertEquals(ChipStatus.NotAvailable, c.status)
        assertNull(c.primary)
        assertEquals(HeyJandalAction.TurnOff, c.secondary)
        assertEquals("Stays off until this is fixed.", c.caveat)
        assertNull(c.why)
    }

    @Test
    fun `model not installed while chosen is NotAvailable, stays off, and offers Turn off`() {
        val c = card(stored = false, choice = true, platform = ready.copy(wakeModelAvailable = false))
        assertEquals(ChipStatus.NotAvailable, c.status)
        assertNull(c.primary)
        assertEquals(HeyJandalAction.TurnOff, c.secondary)
        assertEquals("Stays off until this is fixed.", c.caveat)
        assertTrue(c.description.contains("model"))
    }

    @Test
    fun `microphone revoked while chosen is NeedsAccess with Allow microphone and Turn off`() {
        val c = card(stored = true, choice = true, platform = ready.copy(micGranted = false))
        assertEquals(ChipStatus.NeedsAccess, c.status)
        assertEquals(HeyJandalAction.AllowMicrophone, c.primary)
        assertEquals(HeyJandalAction.TurnOff, c.secondary)
        assertEquals("Hey Jandal needs microphone access. Stays off until this is fixed.", c.caveat)
        assertNull(c.why)
    }

    @Test
    fun `microphone revoked while not chosen is Off with Allow microphone and plain block reason`() {
        val c = card(stored = false, choice = false, platform = ready.copy(micGranted = false))
        assertEquals(ChipStatus.Off, c.status)
        assertEquals(HeyJandalAction.AllowMicrophone, c.primary)
        assertNull(c.secondary)
        assertEquals("Hey Jandal needs microphone access.", c.caveat)
        assertNotNull(c.why)
    }

    @Test
    fun `usage access needed while chosen is NeedsAccess with Open Usage Access and Turn off`() {
        val c = card(stored = true, choice = true, platform = ready.copy(usageAccessRequired = true))
        assertEquals(ChipStatus.NeedsAccess, c.status)
        assertEquals(HeyJandalAction.OpenUsageAccess, c.primary)
        assertEquals(HeyJandalAction.TurnOff, c.secondary)
        assertTrue(c.caveat!!.contains("Usage Access"))
        assertTrue(c.caveat!!.endsWith("Stays off until this is fixed."))
        assertNull(c.why)
    }

    @Test
    fun `usage access needed while not chosen is Off with Open Usage Access and plain block reason`() {
        val c = card(stored = false, choice = false, platform = ready.copy(usageAccessRequired = true))
        assertEquals(ChipStatus.Off, c.status)
        assertEquals(HeyJandalAction.OpenUsageAccess, c.primary)
        assertNull(c.secondary)
        assertTrue(c.caveat!!.contains("Usage Access"))
        assertFalse(c.caveat!!.contains("Stays off"))
    }

    @Test
    fun `a blocked row never says it turns on when Finish is tapped`() {
        val blocked = listOf(
            ready.copy(micGranted = false),
            ready.copy(usageAccessRequired = true),
            ready.copy(hasMicrophone = false),
            ready.copy(wakeModelAvailable = false),
        )
        for (platform in blocked) {
            for (choice in listOf(true, false)) {
                val c = card(stored = false, choice = choice, platform = platform)
                assertFalse(c.description.contains("Turns on when you tap Finish."), "choice=$choice on $platform")
            }
        }
    }

    @Test
    fun `download size is named when extra bytes are needed`() {
        val c = card(stored = false, choice = false, extraBytes = 75_000_000L)
        assertTrue(c.caveat!!.startsWith("Adds a "))
        assertTrue(c.caveat!!.endsWith("download. Uses a little battery."))
    }

    @Test
    fun `the default assistant never blocks Hey Jandal`() {
        val noRole = ready.copy(assistantRoleAvailable = false, assistantRoleHeld = false)
        val c = card(stored = true, choice = true, platform = noRole)
        assertEquals(ChipStatus.On, c.status)
        assertEquals(HeyJandalAction.TurnOff, c.secondary)
        assertNull(c.caveat?.takeIf { it.contains("assistant") })
    }
}
