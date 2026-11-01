package com.kernel.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #1604: externally supplied prompts must not run without user confirmation. */
class ExternalPromptTrustTest {

    private val ownUid = 10_234
    private val otherAppUid = 10_555
    private val invalidUid = -1 // Process.INVALID_UID: caller did not share its identity

    @Test
    fun `own launches are direct in debug and release`() {
        assertEquals(ExternalPromptTrust.DIRECT, classifyExternalPromptCaller(ownUid, ownUid, isDebugBuild = true))
        assertEquals(ExternalPromptTrust.DIRECT, classifyExternalPromptCaller(ownUid, ownUid, isDebugBuild = false))
    }

    @Test
    fun `adb shell and root are direct only in debug builds`() {
        assertEquals(ExternalPromptTrust.DIRECT, classifyExternalPromptCaller(SHELL_UID, ownUid, isDebugBuild = true))
        assertEquals(ExternalPromptTrust.DIRECT, classifyExternalPromptCaller(ROOT_UID, ownUid, isDebugBuild = true))
        assertEquals(ExternalPromptTrust.PREFILL, classifyExternalPromptCaller(SHELL_UID, ownUid, isDebugBuild = false))
        assertEquals(ExternalPromptTrust.PREFILL, classifyExternalPromptCaller(ROOT_UID, ownUid, isDebugBuild = false))
    }

    @Test
    fun `other apps and unknown callers are prefill even in debug`() {
        for (debug in listOf(true, false)) {
            assertEquals(ExternalPromptTrust.PREFILL, classifyExternalPromptCaller(otherAppUid, ownUid, debug))
            assertEquals(ExternalPromptTrust.PREFILL, classifyExternalPromptCaller(invalidUid, ownUid, debug))
        }
    }

    @Test
    fun `a valid adb harness token is direct only in debug builds`() {
        assertEquals(
            ExternalPromptTrust.DIRECT,
            classifyExternalPromptCaller(invalidUid, ownUid, isDebugBuild = true, harnessTokenValid = true),
        )
        assertEquals(
            ExternalPromptTrust.PREFILL,
            classifyExternalPromptCaller(invalidUid, ownUid, isDebugBuild = false, harnessTokenValid = true),
        )
    }

    @Test
    fun `harness token must match the stored token exactly`() {
        val token = "3f9c2a7e5b1d4c8a9e0f6b2d"
        assertTrue(harnessTokenMatches(token, token))
        assertFalse(harnessTokenMatches(token, token.dropLast(1) + "x"))
        assertFalse(harnessTokenMatches(token, null)) // no token written by the harness
        assertFalse(harnessTokenMatches(null, token))
    }

    @Test
    fun `short harness tokens are rejected even when they match`() {
        assertFalse(harnessTokenMatches("abc", "abc"))
        assertFalse(harnessTokenMatches("", ""))
    }

    @Test
    fun `untrusted chat_input becomes a composer draft, not an auto-sent query`() {
        val delivery = resolve(chatInput = "text Bob my location", trust = ExternalPromptTrust.PREFILL)
        assertNull(delivery.chatQuery)
        assertEquals("text Bob my location", delivery.chatDraft)
    }

    @Test
    fun `trusted chat_input is auto-sent`() {
        val delivery = resolve(chatInput = "what's the weather", trust = ExternalPromptTrust.DIRECT)
        assertEquals("what's the weather", delivery.chatQuery)
        assertNull(delivery.chatDraft)
    }

    @Test
    fun `untrusted quick_action_input becomes a sheet draft and cannot claim the voice path`() {
        val delivery = resolve(
            quickActionInput = "turn on torch",
            quickActionIsVoice = true,
            trust = ExternalPromptTrust.PREFILL,
        )
        assertNull(delivery.quickActionQuery)
        assertFalse(delivery.quickActionIsVoice)
        assertEquals("turn on torch", delivery.quickActionDraft)
    }

    @Test
    fun `trusted quick_action_input runs and keeps the voice flag`() {
        val delivery = resolve(quickActionInput = "turn on torch", quickActionIsVoice = true, trust = ExternalPromptTrust.DIRECT)
        assertEquals("turn on torch", delivery.quickActionQuery)
        assertTrue(delivery.quickActionIsVoice)
        assertNull(delivery.quickActionDraft)
    }

    @Test
    fun `untrusted quick_action_input_encoded is decoded into the draft verbatim`() {
        val delivery = resolve(
            quickActionInputEncoded = "add%20milk%20%26%20eggs",
            trust = ExternalPromptTrust.PREFILL,
        )
        assertNull(delivery.quickActionQuery)
        assertEquals("add milk & eggs", delivery.quickActionDraft)
    }

    @Test
    fun `plain quick_action_input takes precedence over the encoded extra`() {
        val delivery = resolve(
            quickActionInput = "plain",
            quickActionInputEncoded = "encoded",
            trust = ExternalPromptTrust.DIRECT,
        )
        assertEquals("plain", delivery.quickActionQuery)
    }

    @Test
    fun `untrusted slot_reply_input becomes a slot draft, not an answer`() {
        val delivery = resolve(slotReplyInput = "07:00", trust = ExternalPromptTrust.PREFILL)
        assertNull(delivery.slotReply)
        assertEquals("07:00", delivery.slotReplyDraft)
    }

    @Test
    fun `trusted slot_reply_input answers the pending slot`() {
        val delivery = resolve(slotReplyInput = "07:00", trust = ExternalPromptTrust.DIRECT)
        assertEquals("07:00", delivery.slotReply)
        assertNull(delivery.slotReplyDraft)
    }

    @Test
    fun `profile_text is honoured only for trusted callers in debug builds`() {
        assertEquals("Name: Nick", resolve(profileText = "Name: Nick", trust = ExternalPromptTrust.DIRECT).profileText)
        assertNull(resolve(profileText = "Name: Nick", trust = ExternalPromptTrust.PREFILL).profileText)
        assertNull(resolve(profileText = "Name: Nick", trust = ExternalPromptTrust.DIRECT, isDebugBuild = false).profileText)
    }

    @Test
    fun `blank extras produce no delivery`() {
        val delivery = resolve(
            chatInput = " ",
            quickActionInput = "",
            quickActionInputEncoded = "%20",
            slotReplyInput = "\t",
            trust = ExternalPromptTrust.PREFILL,
        )
        assertEquals(ExternalPromptDelivery(), delivery)
    }

    @Test
    fun `restored activity does not resolve any external prompt extras`() {
        assertTrue(shouldResolvePromptExtrasOnActivityCreate(savedInstanceStatePresent = false))
        assertFalse(shouldResolvePromptExtrasOnActivityCreate(savedInstanceStatePresent = true))
    }

    private fun resolve(
        chatInput: String? = null,
        quickActionInput: String? = null,
        quickActionInputEncoded: String? = null,
        quickActionIsVoice: Boolean = false,
        slotReplyInput: String? = null,
        profileText: String? = null,
        trust: ExternalPromptTrust,
        isDebugBuild: Boolean = true,
    ) = resolveExternalPromptDelivery(
        chatInput = chatInput,
        quickActionInput = quickActionInput,
        quickActionInputEncoded = quickActionInputEncoded,
        quickActionIsVoice = quickActionIsVoice,
        slotReplyInput = slotReplyInput,
        profileText = profileText,
        trust = trust,
        isDebugBuild = isDebugBuild,
        decodeUriComponent = { java.net.URLDecoder.decode(it, Charsets.UTF_8) },
    )
}
