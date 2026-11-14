package com.kernel.ai.core.inference

import java.io.File
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NzTruthDefinitionInstructionTest {

    @Test
    fun `definitions do not contain model instruction wording`() {
        val asset = sequenceOf(
            File("src/main/assets/nz_truth_memories.json"),
            File("core/inference/src/main/assets/nz_truth_memories.json"),
        ).firstOrNull(File::isFile)
        assertTrue(asset != null, "Could not locate nz_truth_memories.json from ${File(".").absolutePath}")

        val entries = JSONArray(requireNotNull(asset).readText())
        val instructionMarkers = listOf("the user", "respond with", "YOU ", "DO NOT", "ALWAYS", "NEVER")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val definition = entry.getString("definition")
            assertFalse(
                instructionMarkers.any(definition::contains),
                "${entry.getString("id")} definition contains model instruction wording: $definition",
            )
        }
    }
}
