package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.hardware.HardwareTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelRolesTest {

    private val e4b = KernelModel.GEMMA_4_E4B
    private val e2b = KernelModel.GEMMA_4_E2B
    private val arcticM = KernelModel.ARCTIC_EMBED_M_V1_5
    private val arcticVocab = KernelModel.ARCTIC_EMBED_V1_5_VOCAB

    /** Every model is NotDownloaded except the ones listed, which are Downloaded. */
    private fun states(vararg downloaded: KernelModel): Map<KernelModel, DownloadState> =
        KernelModel.entries.associateWith {
            if (it in downloaded) DownloadState.Downloaded("/models/${it.name}") else DownloadState.NotDownloaded
        }

    private fun chat(section: List<ModelRoleSection>) = section.first { it.role == ModelRole.CHAT_BRAIN }
    private fun memory(section: List<ModelRoleSection>) = section.first { it.role == ModelRole.MEMORY_SEARCH }

    // ── Chat brain readiness by tier ─────────────────────────────────────────

    @Test
    fun `mid range with only E-2B is ready and uses E-2B`() {
        val s = ModelRoles.sections(null, HardwareTier.MID_RANGE, states(e2b))
        assertEquals(RoleStatus.READY, chat(s).status)
        assertEquals(e2b, chat(s).using)
    }

    @Test
    fun `mid range with only E-4B is not ready and asks for E-2B`() {
        val st = states(e4b)
        val s = ModelRoles.sections(null, HardwareTier.MID_RANGE, st)
        assertEquals(RoleStatus.MISSING, chat(s).status)
        assertNull(chat(s).using)
        assertEquals(listOf(e2b), ModelRoles.stillNeeded(HardwareTier.MID_RANGE, st).first { it.role == ModelRole.CHAT_BRAIN }.downloadModels)
    }

    @Test
    fun `low power behaves like mid range`() {
        assertEquals(RoleStatus.READY, chat(ModelRoles.sections(null, HardwareTier.LOW_POWER, states(e2b))).status)
        assertEquals(RoleStatus.MISSING, chat(ModelRoles.sections(null, HardwareTier.LOW_POWER, states(e4b))).status)
    }

    @Test
    fun `flagship with only E-4B is ready and uses E-4B`() {
        val s = ModelRoles.sections(null, HardwareTier.FLAGSHIP, states(e4b))
        assertEquals(RoleStatus.READY, chat(s).status)
        assertEquals(e4b, chat(s).using)
    }

    @Test
    fun `non-flagship offers E-4B as not selectable even when installed`() {
        val s = ModelRoles.sections(null, HardwareTier.MID_RANGE, states(e2b, e4b))
        val rows = chat(s).rows.associateBy { it.model }
        assertFalse(rows.getValue(e4b).selectable)
        assertTrue(rows.getValue(e2b).selectable)
    }

    @Test
    fun `flagship offers both chat models as selectable`() {
        val s = ModelRoles.sections(null, HardwareTier.FLAGSHIP, states(e2b, e4b))
        assertTrue(chat(s).rows.all { it.selectable })
    }

    @Test
    fun `saved pick that is not installed falls back to the tier default`() {
        val s = ModelRoles.sections(e2b, HardwareTier.FLAGSHIP, states(e4b))
        assertEquals(e4b, chat(s).using)
        assertEquals(e2b, chat(s).preferred)
    }

    @Test
    fun `chat still needed on flagship with nothing installed downloads E-4B`() {
        val items = ModelRoles.stillNeeded(HardwareTier.FLAGSHIP, states())
        assertEquals(listOf(e4b), items.first { it.role == ModelRole.CHAT_BRAIN }.downloadModels)
    }

    // ── Deletion guard ───────────────────────────────────────────────────────

    @Test
    fun `mid range deleting E-2B breaks the app even when E-4B is installed`() {
        val st = states(e2b, e4b, arcticM, arcticVocab)
        assertTrue(ModelRoles.deletionBreaksApp(e2b, HardwareTier.MID_RANGE, st))
    }

    @Test
    fun `flagship deleting E-2B does not break the app while E-4B is installed`() {
        val st = states(e2b, e4b, arcticM, arcticVocab)
        assertFalse(ModelRoles.deletionBreaksApp(e2b, HardwareTier.FLAGSHIP, st))
    }

    @Test
    fun `flagship deleting E-4B does not break the app while E-2B is installed`() {
        val st = states(e2b, e4b, arcticM, arcticVocab)
        assertFalse(ModelRoles.deletionBreaksApp(e4b, HardwareTier.FLAGSHIP, st))
    }

    @Test
    fun `deleting a required memory file breaks the app`() {
        val st = states(e2b, arcticM, arcticVocab)
        assertTrue(ModelRoles.deletionBreaksApp(arcticVocab, HardwareTier.MID_RANGE, st))
        assertTrue(ModelRoles.deletionBreaksApp(arcticM, HardwareTier.LOW_POWER, st))
    }

    @Test
    fun `quick commands model never breaks the app`() {
        assertFalse(ModelRoles.deletionBreaksApp(KernelModel.MINI_LM, HardwareTier.MID_RANGE, states(KernelModel.MINI_LM)))
    }

    // ── Memory search ────────────────────────────────────────────────────────

    @Test
    fun `memory still needed downloads both files when both are missing`() {
        val items = ModelRoles.stillNeeded(HardwareTier.MID_RANGE, states(e2b))
        assertEquals(listOf(arcticM, arcticVocab), items.first { it.role == ModelRole.MEMORY_SEARCH }.downloadModels)
    }

    @Test
    fun `memory still needed downloads only the missing file`() {
        val items = ModelRoles.stillNeeded(HardwareTier.MID_RANGE, states(e2b, arcticM))
        assertEquals(listOf(arcticVocab), items.first { it.role == ModelRole.MEMORY_SEARCH }.downloadModels)
    }

    @Test
    fun `memory is ready only when both files are installed`() {
        assertEquals(RoleStatus.READY, memory(ModelRoles.sections(null, HardwareTier.MID_RANGE, states(arcticM, arcticVocab))).status)
        assertEquals(RoleStatus.MISSING, memory(ModelRoles.sections(null, HardwareTier.MID_RANGE, states(arcticM))).status)
    }

    @Test
    fun `everything installed on mid range leaves nothing still needed`() {
        assertTrue(ModelRoles.stillNeeded(HardwareTier.MID_RANGE, states(e2b, arcticM, arcticVocab)).isEmpty())
    }

    // ── Regressions from round-2 review ──────────────────────────────────────

    @Test
    fun `mid range E-4B downloading with E-2B missing still asks for E-2B`() {
        val st: Map<KernelModel, DownloadState> = KernelModel.entries.associateWith { m ->
            if (m == e4b) DownloadState.Downloading(progress = 0.4f) else DownloadState.NotDownloaded
        }
        val s = ModelRoles.sections(null, HardwareTier.MID_RANGE, st)
        assertEquals(RoleStatus.MISSING, chat(s).status)
        val item = ModelRoles.stillNeeded(HardwareTier.MID_RANGE, st).first { it.role == ModelRole.CHAT_BRAIN }
        assertEquals(RoleStatus.MISSING, item.status)
        assertEquals(listOf(e2b), item.downloadModels)
        assertNull(item.progress)
    }

    @Test
    fun `flagship E-4B downloading still reports chat as downloading`() {
        val st: Map<KernelModel, DownloadState> = KernelModel.entries.associateWith { m ->
            if (m == e4b) DownloadState.Downloading(progress = 0.4f) else DownloadState.NotDownloaded
        }
        assertEquals(RoleStatus.DOWNLOADING, chat(ModelRoles.sections(null, HardwareTier.FLAGSHIP, st)).status)
        assertEquals(0.4f, ModelRoles.stillNeeded(HardwareTier.FLAGSHIP, st).first { it.role == ModelRole.CHAT_BRAIN }.progress)
    }

    @Test
    fun `mid range old E-4B preference runs E-2B and keeps E-2B required`() {
        val st = states(e2b, e4b, arcticM, arcticVocab)
        val section = chat(ModelRoles.sections(e4b, HardwareTier.MID_RANGE, st))
        assertEquals(e2b, section.using)
        assertEquals(RoleStatus.READY, section.status)
        val rows = section.rows.associateBy { it.model }
        assertTrue(rows.getValue(e2b).required)
        assertEquals(ModelGroup.IN_USE, rows.getValue(e2b).group)
        assertFalse(rows.getValue(e4b).selectable)
        assertTrue(ModelRoles.deletionBreaksApp(e2b, HardwareTier.MID_RANGE, st))
    }

    @Test
    fun `E-4B is not allowed on phone below flagship, while a not-yet-downloaded E-2B still is`() {
        val mid = chat(ModelRoles.sections(null, HardwareTier.MID_RANGE, states())).rows.associateBy { it.model }
        assertFalse(mid.getValue(e4b).allowedOnPhone)
        assertTrue(mid.getValue(e2b).allowedOnPhone)
        assertFalse(mid.getValue(e2b).selectable)
        val flagship = chat(ModelRoles.sections(null, HardwareTier.FLAGSHIP, states())).rows.associateBy { it.model }
        assertTrue(flagship.getValue(e4b).allowedOnPhone)
    }
}
