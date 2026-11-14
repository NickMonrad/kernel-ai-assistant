package com.kernel.ai.core.model.availability

import com.kernel.ai.core.inference.download.DownloadState
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.hardware.HardwareTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelRolesTest {

    private val done = DownloadState.Downloaded("/x")
    private val none = DownloadState.NotDownloaded

    private fun states(vararg downloaded: KernelModel): Map<KernelModel, DownloadState> =
        KernelModel.entries.associateWith { if (it in downloaded) done else none }

    private fun chat(sections: List<ModelRoleSection>) = sections.first { it.role == ModelRole.CHAT_BRAIN }
    private fun memory(sections: List<ModelRoleSection>) = sections.first { it.role == ModelRole.MEMORY_SEARCH }
    private fun ModelRoleSection.row(model: KernelModel) = rows.first { it.model == model }

    // ── E-2B is never a plain missing Download when E-4B is in use ─────────

    @Test
    fun `E-2B is a More models row, not a plain download, when E-4B is installed and in use`() {
        val s = states(KernelModel.GEMMA_4_E4B, KernelModel.EMBEDDING_GEMMA_300M, KernelModel.EMBEDDING_GEMMA_SP_MODEL)
        val section = chat(ModelRoles.sections(preferred = null, tier = HardwareTier.FLAGSHIP, states = s))

        assertEquals(KernelModel.GEMMA_4_E4B, section.using)
        assertEquals(ModelGroup.MORE_MODELS, section.row(KernelModel.GEMMA_4_E2B).group)
        assertEquals(ModelGroup.IN_USE, section.row(KernelModel.GEMMA_4_E4B).group)
    }

    @Test
    fun `E-2B is Optional with Delete when it is installed but E-4B is in use`() {
        val s = states(KernelModel.GEMMA_4_E4B, KernelModel.GEMMA_4_E2B)
        val section = chat(ModelRoles.sections(preferred = null, tier = HardwareTier.FLAGSHIP, states = s))

        assertEquals(ModelGroup.OPTIONAL, section.row(KernelModel.GEMMA_4_E2B).group)
        assertFalse(section.row(KernelModel.GEMMA_4_E2B).inUse)
    }

    @Test
    fun `E-2B chosen by the user is in use and E-4B stays downloadable`() {
        val s = states(KernelModel.GEMMA_4_E4B, KernelModel.GEMMA_4_E2B)
        val section = chat(ModelRoles.sections(preferred = KernelModel.GEMMA_4_E2B, tier = HardwareTier.FLAGSHIP, states = s))

        assertEquals(KernelModel.GEMMA_4_E2B, section.using)
        assertTrue(section.row(KernelModel.GEMMA_4_E2B).inUse)
        assertEquals(ModelGroup.OPTIONAL, section.row(KernelModel.GEMMA_4_E4B).group)
    }

    // ── Tier: E-4B is not needed on a non-flagship phone ───────────────────

    @Test
    fun `E-4B is Not needed on this phone when the tier is not flagship`() {
        val section = chat(ModelRoles.sections(preferred = null, tier = HardwareTier.MID_RANGE, states = states()))

        assertEquals(ModelGroup.NOT_NEEDED, section.row(KernelModel.GEMMA_4_E4B).group)
        assertEquals(ModelGroup.IN_USE, section.row(KernelModel.GEMMA_4_E2B).group)
        assertTrue(section.row(KernelModel.GEMMA_4_E2B).required)
    }

    // ── Still needed ──────────────────────────────────────────────────────

    @Test
    fun `nothing installed lists chat brain and memory search as still needed`() {
        val items = ModelRoles.stillNeeded(HardwareTier.FLAGSHIP, states())

        assertEquals(listOf(ModelRole.CHAT_BRAIN, ModelRole.MEMORY_SEARCH), items.map { it.role })
        assertEquals(KernelModel.GEMMA_4_E4B, items.first { it.role == ModelRole.CHAT_BRAIN }.downloadModel)
        assertEquals(KernelModel.EMBEDDING_GEMMA_300M, items.first { it.role == ModelRole.MEMORY_SEARCH }.downloadModel)
        assertEquals(RoleStatus.MISSING, items.first { it.role == ModelRole.CHAT_BRAIN }.status)
    }

    @Test
    fun `E-2B alone satisfies chat, so chat is not still needed`() {
        val items = ModelRoles.stillNeeded(HardwareTier.FLAGSHIP, states(KernelModel.GEMMA_4_E2B))

        assertTrue(items.none { it.role == ModelRole.CHAT_BRAIN })
    }

    @Test
    fun `memory search with only one of its two files is still needed, pointing at the missing file`() {
        val s = states(KernelModel.GEMMA_4_E4B, KernelModel.EMBEDDING_GEMMA_300M)
        val item = ModelRoles.stillNeeded(HardwareTier.FLAGSHIP, s).single()

        assertEquals(ModelRole.MEMORY_SEARCH, item.role)
        assertEquals(RoleStatus.MISSING, item.status)
        assertEquals(KernelModel.EMBEDDING_GEMMA_SP_MODEL, item.downloadModel)
    }

    @Test
    fun `everything required installed leaves no still-needed lines`() {
        val s = states(
            KernelModel.GEMMA_4_E4B,
            KernelModel.EMBEDDING_GEMMA_300M,
            KernelModel.EMBEDDING_GEMMA_SP_MODEL,
        )
        assertTrue(ModelRoles.stillNeeded(HardwareTier.FLAGSHIP, s).isEmpty())
    }

    @Test
    fun `an in-progress download reports DOWNLOADING with its progress`() {
        val s = states(KernelModel.EMBEDDING_GEMMA_300M, KernelModel.EMBEDDING_GEMMA_SP_MODEL) +
            (KernelModel.GEMMA_4_E4B to DownloadState.Downloading(progress = 0.42f))
        val item = ModelRoles.stillNeeded(HardwareTier.FLAGSHIP, s).single()

        assertEquals(RoleStatus.DOWNLOADING, item.status)
        assertEquals("Chat brain: downloading 42%", ModelRoleCopy.stillNeededLabel(item))
    }

    // ── Delete guard ──────────────────────────────────────────────────────

    @Test
    fun `deleting the only chat model breaks the role`() {
        val s = states(KernelModel.GEMMA_4_E4B)
        assertTrue(ModelRoles.deletionBreaksRole(KernelModel.GEMMA_4_E4B, s))
    }

    @Test
    fun `deleting E-2B while E-4B is installed does not break chat`() {
        val s = states(KernelModel.GEMMA_4_E4B, KernelModel.GEMMA_4_E2B)
        assertFalse(ModelRoles.deletionBreaksRole(KernelModel.GEMMA_4_E2B, s))
    }

    @Test
    fun `deleting a required memory file breaks memory search`() {
        val s = states(KernelModel.EMBEDDING_GEMMA_300M, KernelModel.EMBEDDING_GEMMA_SP_MODEL)
        assertTrue(ModelRoles.deletionBreaksRole(KernelModel.EMBEDDING_GEMMA_SP_MODEL, s))
    }

    @Test
    fun `a bundled model never breaks a role`() {
        assertFalse(ModelRoles.deletionBreaksRole(KernelModel.MINI_LM, states()))
    }

    // ── Chat brain resolution ─────────────────────────────────────────────

    @Test
    fun `chat falls back to the other model when the preferred one is not installed`() {
        val s = states(KernelModel.GEMMA_4_E2B)
        assertEquals(
            KernelModel.GEMMA_4_E2B,
            ModelRoles.chatBrainUsing(preferred = KernelModel.GEMMA_4_E4B, tier = HardwareTier.FLAGSHIP, states = s),
        )
    }

    @Test
    fun `chat is null when nothing is installed`() {
        assertEquals(null, ModelRoles.chatBrainUsing(null, HardwareTier.FLAGSHIP, states()))
    }
}
