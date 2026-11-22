package com.kernel.ai.feature.chat.setup

import com.kernel.ai.core.inference.download.DownloadConsent
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.voice.Asr4AllInstall
import com.kernel.ai.core.voice.InflectMicroSetup
import com.kernel.ai.core.voice.SherpaPiperVoice
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Step 3 rows (README "Done when"): driven by the plan and choices, by tier, downloaded items hidden, Inflect only when chosen. */
class SetupDownloadRowsTest {

    private val arctic = listOf(KernelModel.ARCTIC_EMBED_M_V1_5, KernelModel.ARCTIC_EMBED_V1_5_VOCAB)
    private val asr4all = Asr4AllInstall.models(Asr4AllSize.M)

    private fun total(models: List<KernelModel>, voices: List<SherpaPiperVoice>) =
        DownloadConsent.firstRunEstimateBytes(models, voices.sumOf { it.approxDownloadBytes })

    @Test
    fun `a flagship plan lists E-4B, memory, offline speech and Cori, adding up to the total`() {
        val models = listOf(KernelModel.GEMMA_4_E4B) + arctic + asr4all
        val voices = listOf(SherpaPiperVoice.CoriHigh)

        val rows = downloadRows(models, voices)

        assertEquals(
            listOf("Chat and answers", "Memory and search", "Offline speech recognition", "Cori natural voice"),
            rows.map { it.plainName },
        )
        assertEquals(KernelModel.GEMMA_4_E4B.displayName, rows.first().technicalName)
        assertEquals(arctic.sumOf { it.approxSizeBytes }, rows[1].bytes)
        assertEquals(asr4all.sumOf { it.approxSizeBytes }, rows[2].bytes)
        assertEquals(total(models, voices), rows.sumOf { it.bytes })
    }

    @Test
    fun `a mid-range plan names E-2B as chat and answers`() {
        val rows = downloadRows(listOf(KernelModel.GEMMA_4_E2B) + arctic, emptyList())

        assertEquals("Chat and answers", rows.first().plainName)
        assertEquals(KernelModel.GEMMA_4_E2B.displayName, rows.first().technicalName)
        assertEquals(KernelModel.GEMMA_4_E2B.approxSizeBytes, rows.first().bytes)
    }

    @Test
    fun `items already downloaded are not in the plan and get no row`() {
        // Gemma and Cori are on the phone, so the view model leaves them out of the plan.
        val rows = downloadRows(arctic, emptyList())

        assertEquals(listOf("Memory and search"), rows.map { it.plainName })
    }

    @Test
    fun `nothing planned gives no rows and a zero total`() {
        val rows = downloadRows(emptyList(), emptyList())

        assertTrue(rows.isEmpty())
        assertEquals(0L, rows.sumOf { it.bytes })
    }

    @Test
    fun `Inflect chosen adds one Inflect Micro row and its Cori pack row`() {
        val inflect = InflectMicroSetup.kernelModels()
        val models = listOf(KernelModel.GEMMA_4_E4B) + inflect
        val voices = listOf(SherpaPiperVoice.CoriHigh)

        val rows = downloadRows(models, voices)

        assertEquals(listOf("Chat and answers", "Cori natural voice", "Inflect Micro"), rows.map { it.plainName })
        assertEquals(inflect.sumOf { it.approxSizeBytes }, rows.last().bytes)
        assertEquals(total(models, voices), rows.sumOf { it.bytes })
    }

    @Test
    fun `without Inflect in the plan there is no Inflect row`() {
        val rows = downloadRows(listOf(KernelModel.GEMMA_4_E4B), listOf(SherpaPiperVoice.CoriHigh))

        assertTrue(rows.none { it.plainName == "Inflect Micro" })
    }

    @Test
    fun `any other planned model still gets a row, so rows always sum to the total`() {
        val models = listOf(KernelModel.GEMMA_4_E4B, KernelModel.MINI_LM)

        val rows = downloadRows(models, emptyList())

        assertEquals(2, rows.size)
        assertEquals(total(models, emptyList()), rows.sumOf { it.bytes })
    }
}
