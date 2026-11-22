package com.kernel.ai.core.inference.terms

import com.kernel.ai.core.inference.download.KernelModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TermsDownloadGateTest {

    private val asr4all = KernelModel.entries.filter { it.requiresTermsAcceptance }

    @Test
    fun `the gate covers all ten asr4all files`() {
        assertEquals(10, asr4all.size)
    }

    @Test
    fun `no asr4all file downloads without the current acceptance`() {
        val refusals = listOf(
            TermsAcceptanceRecord(),
            TermsAcceptanceRecord(dismissedVersion = TERMS_VERSION),
            TermsAcceptanceRecord(acceptedVersion = "2025-01-01"),
        )
        asr4all.forEach { model ->
            refusals.forEach { record ->
                assertFalse(termsAllowDownload(model, record), "${model.name} with $record")
            }
        }
    }

    @Test
    fun `the current acceptance releases every asr4all file`() {
        asr4all.forEach { model ->
            assertTrue(termsAllowDownload(model, TermsAcceptanceRecord(acceptedVersion = TERMS_VERSION)), model.name)
        }
    }

    @Test
    fun `other models are never blocked by the terms`() {
        KernelModel.entries.filterNot { it.requiresTermsAcceptance }.forEach { model ->
            assertTrue(termsAllowDownload(model, TermsAcceptanceRecord()), model.name)
        }
    }
}
