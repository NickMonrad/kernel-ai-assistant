package com.kernel.ai.core.voice.asr4all

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PcecTextRendererTest {
    private val pcec = Asr4AllTestData.configM().pcec
    private val renderer = PcecTextRenderer(pcec)
    private val clip = Asr4AllTestData.Clip0000

    @Test
    fun `recorded PCEC slots render to punctuated cased text`() {
        val stream = clip.streamSlots
        val flushUsed = pcec.slotsPerToken * (pcec.lagTokens + clip.flushN) + 1
        val ids = renderer.collapse(*stream.map { IntSlice(it) }.toTypedArray(), IntSlice(clip.flushSlots, 0, flushUsed))

        assertEquals(
            "Mrister quilter is the apostle of the middle classes, and we are glad to welcome his gospel.",
            renderer.render(ids),
        )
    }

    @Test
    fun `repeats collapse across call boundaries but not across blanks`() {
        val a = id(" the")
        val blank = pcec.blankId
        val ids = renderer.collapse(IntSlice(intArrayOf(blank, a)), IntSlice(intArrayOf(a, blank, a)))

        assertEquals(listOf(a, a), ids.toList())
    }

    @Test
    fun `control ids apply capitals and marks`() {
        val ids = intArrayOf(
            pcec.periodId, // nothing to attach to: dropped
            id(" i"), id("t"), pcec.commaId,
            pcec.allCapsId, id(" n"), id("as"), id("a"),
            pcec.capId, id(" s"), id("ays"), pcec.questionId,
            id(" y"), id("es"), pcec.periodId,
        )

        assertEquals("It, NASA Says? Yes.", renderer.render(ids))
    }

    private fun id(piece: String): Int = pcec.vocab.indexOf(piece).also { check(it >= 0) { "no piece '$piece'" } }
}
