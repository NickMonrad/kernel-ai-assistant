package com.kernel.ai.feature.settings

import androidx.compose.runtime.saveable.SaverScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContactAliasPrefillSaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(value: ContactAliasPrefill?): ContactAliasPrefill? {
        val saved = with(ContactAliasPrefillSaver) { scope.save(value) }
        return ContactAliasPrefillSaver.restore(saved!!)
    }

    @Test
    fun `pending call prefill survives save and restore with its call intent`() {
        val prefill = ContactAliasPrefill(
            alias = "laurel",
            openAdd = true,
            openPicker = true,
            suggestedName = "Laurelle Smith",
            callRequested = true,
        )

        assertEquals(prefill, roundTrip(prefill))
    }

    @Test
    fun `consumed prefill restores as null so it is never reused`() {
        assertNull(roundTrip(null))
    }
}
