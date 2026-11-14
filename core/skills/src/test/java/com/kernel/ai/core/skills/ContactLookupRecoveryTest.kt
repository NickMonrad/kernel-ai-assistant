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
    fun `near match does not suggest Kathryn for Katherine (no shared prefix, documented miss)`() {
        val matches = ContactLookupRecovery.nearMatches("Katherine", listOf("Kathryn Lee"))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `near match finds a contact by surname`() {
        assertEquals(listOf("Laurelle Smith"), ContactLookupRecovery.nearMatches("Smith", listOf("Laurelle Smith")))
    }

    @ParameterizedTest(name = "\"{0}\" does not suggest \"{1}\"")
    @CsvSource(
        "John, Joan",
        "John, Jane",
        "John, Jenny",
        "Kate, Keith",
        "Bill, Bella",
        "Mary, Maria",
        "Mary, Mario",
        "Mary, Myra",
        "Dana, Dan",
        "Dana, Dean",
        "Sam, Sal Jones",
    )
    fun `near match rejects false positives`(query: String, candidate: String) {
        assertTrue(ContactLookupRecovery.nearMatches(query, listOf(candidate)).isEmpty())
    }

    @ParameterizedTest(name = "\"{0}\" does not suggest \"{1}\" (real-world pair)")
    @CsvSource(
        "Rachel, Michel",
        "Martin, Martha",
        "Martin, Marvin",
        "Andrew, Andrea",
        "Olivia, Oliver",
        "Jackson, Jason",
        "Brian, Bruno",
        "Brian, Byron",
        "Peter, Petra",
        "Robin, Ruben",
        "Karen, Corinne",
        "Gordon, Garden",
        "Hannah, Anna",
        "Laurel, Laura",
    )
    fun `near match rejects real-world false positives`(query: String, candidate: String) {
        assertTrue(ContactLookupRecovery.nearMatches(query, listOf(candidate)).isEmpty())
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
    fun `relationship failure keeps Settings guidance in the text and carries one add action`() {
        val result = ContactLookupRecovery.notFoundResult("make_call", "my wife")
        assertEquals("I don't know who your wife is yet. Add them in Settings → People & Contacts.", result.error)
        assertEquals(1, result.actions.size)
        val link = result.actions.single().deepLink
        assertEquals(ContactLookupRecovery.PEOPLE_DESTINATION, link.destination)
        assertEquals("wife", link.args[ContactLookupRecovery.ARG_ALIAS])
        assertEquals("true", link.args[ContactLookupRecovery.ARG_ADD])
        assertNull(link.args[ContactLookupRecovery.ARG_PICK])
    }

    @Test
    fun `unknown name failure keeps Settings guidance and offers add and picker actions`() {
        val result = ContactLookupRecovery.notFoundResult("send_sms", "Sam")
        assertEquals("Couldn't find Sam. Add them in Settings → People & Contacts.", result.error)
        assertEquals(
            listOf("Add in People & Contacts", "Pick from contacts"),
            result.actions.map { it.label },
        )
        assertEquals("true", result.actions[1].deepLink.args[ContactLookupRecovery.ARG_PICK])
    }

    @Test
    fun `call failure marks the link so the add flow may offer to dial`() {
        val result = ContactLookupRecovery.notFoundResult("make_call", "Sam", callRequested = true)
        assertEquals("true", result.actions.first().deepLink.args[ContactLookupRecovery.ARG_CALL])
    }

    @Test
    fun `non-call failure does not mark the link as a call`() {
        val result = ContactLookupRecovery.notFoundResult("send_sms", "Sam")
        assertNull(result.actions.first().deepLink.args[ContactLookupRecovery.ARG_CALL])
    }

    @Test
    fun `single near match asks for confirmation and never dials`() {
        val result = ContactLookupRecovery.notFoundResult(
            "make_call",
            "Laurel",
            nearMatches = listOf("Laurelle Smith"),
        )
        assertEquals(
            "I couldn't find Laurel. Closest contact: Laurelle Smith. Add them in Settings → People & Contacts.",
            result.error,
        )
        val suggestion = result.actions.first()
        assertEquals("Yes, Laurelle Smith", suggestion.label)
        assertEquals("Laurelle Smith", suggestion.deepLink.args[ContactLookupRecovery.ARG_SUGGESTED])
        assertEquals("laurel", suggestion.deepLink.args[ContactLookupRecovery.ARG_ALIAS])
    }

    @Test
    fun `several near matches are named in the text`() {
        val result = ContactLookupRecovery.notFoundResult(
            "make_call",
            "Laurel",
            nearMatches = listOf("Laurelle Smith", "Laurel Day"),
        )
        assertTrue(result.error.contains("Laurelle Smith or Laurel Day"))
    }

    @Test
    fun `leading our and your are not stripped so alias keys match the repository`() {
        val result = ContactLookupRecovery.notFoundResult("send_sms", "our mum")
        assertEquals("our mum", result.actions.first().deepLink.args[ContactLookupRecovery.ARG_ALIAS])
    }
}
