package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ContactLookupRecoveryTest {

    @ParameterizedTest(name = "\"{0}\" is relationship word \"{1}\"")
    @CsvSource(
        "wife, wife",
        "my wife, wife",
        "Mum, mum",
        "the boss, boss",
        "my mom, mom",
        "dad, dad",
    )
    fun `relationship words are detected with determiners and case`(query: String, expected: String) {
        assertEquals(expected, ContactLookupRecovery.relationshipWordOf(query))
    }

    @ParameterizedTest(name = "\"{0}\" is not a relationship word")
    @CsvSource("Sam", "Laurelle", "Margaret Smith", "wifey")
    fun `ordinary names are not relationship words`(query: String) {
        assertNull(ContactLookupRecovery.relationshipWordOf(query))
    }

    @Test
    fun `phonetic key matches Laurel and Laurelle`() {
        assertEquals(ContactLookupRecovery.phoneticKey("Laurel"), ContactLookupRecovery.phoneticKey("Laurelle"))
    }

    @Test
    fun `phonetic key matches Shaun and Sean`() {
        assertEquals(ContactLookupRecovery.phoneticKey("Shaun"), ContactLookupRecovery.phoneticKey("Sean"))
    }

    @Test
    fun `near match finds Laurelle for Laurel`() {
        val matches = ContactLookupRecovery.nearMatches("Laurel", listOf("Laurelle Smith", "Bob Jones"))
        assertEquals(listOf("Laurelle Smith"), matches)
    }

    @Test
    fun `near match finds Kathryn for Katherine`() {
        val matches = ContactLookupRecovery.nearMatches("Katherine", listOf("Kathryn Lee"))
        assertEquals(listOf("Kathryn Lee"), matches)
    }

    @Test
    fun `near match ignores unrelated names and short words`() {
        assertTrue(ContactLookupRecovery.nearMatches("Sam", listOf("Sal Jones", "Tom")).isEmpty())
    }

    @Test
    fun `near match ranks closer edit distance first and caps the list`() {
        val matches = ContactLookupRecovery.nearMatches(
            "Laurel",
            listOf("Laurelle", "Laurel Day", "Laurell", "Laurene"),
            limit = 2,
        )
        assertEquals(2, matches.size)
        assertEquals("Laurel Day", matches.first())
    }

    @Test
    fun `relationship failure carries one add action and no picker`() {
        val result = ContactLookupRecovery.notFoundResult("make_call", "my wife")
        assertEquals("I don't know who your wife is yet.", result.error)
        assertEquals(1, result.actions.size)
        val link = result.actions.single().deepLink
        assertEquals(ContactLookupRecovery.PEOPLE_DESTINATION, link.destination)
        assertEquals("wife", link.args[ContactLookupRecovery.ARG_ALIAS])
        assertEquals("true", link.args[ContactLookupRecovery.ARG_ADD])
        assertNull(link.args[ContactLookupRecovery.ARG_PICK])
    }

    @Test
    fun `unknown name failure offers add and picker actions`() {
        val result = ContactLookupRecovery.notFoundResult("make_call", "Sam")
        assertEquals("Couldn't find Sam.", result.error)
        assertEquals(
            listOf("Add in People & Contacts", "Pick from contacts"),
            result.actions.map { it.label },
        )
        assertEquals("true", result.actions[1].deepLink.args[ContactLookupRecovery.ARG_PICK])
    }

    @Test
    fun `single near match asks for confirmation and never dials`() {
        val result = ContactLookupRecovery.notFoundResult(
            "make_call",
            "Laurel",
            nearMatches = listOf("Laurelle Smith"),
        )
        assertEquals("I couldn't find Laurel. Did you mean Laurelle Smith?", result.error)
        val suggestion = result.actions.first()
        assertEquals("Yes, Laurelle Smith", suggestion.label)
        assertEquals("Laurelle Smith", suggestion.deepLink.args[ContactLookupRecovery.ARG_SUGGESTED])
        assertEquals("laurel", suggestion.deepLink.args[ContactLookupRecovery.ARG_ALIAS])
        assertTrue(result.actions.none { it.deepLink.args.containsKey("call") })
    }

    @Test
    fun `failure result is a Failure so text-only surfaces keep working`() {
        val result = ContactLookupRecovery.notFoundResult("send_sms", "Sam")
        assertTrue(result is SkillResult.Failure)
        assertEquals("send_sms", (result as SkillResult.Failure).skillName)
    }
}
