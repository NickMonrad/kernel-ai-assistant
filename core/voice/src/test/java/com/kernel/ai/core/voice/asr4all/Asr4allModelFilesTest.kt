package com.kernel.ai.core.voice.asr4all

import com.kernel.ai.core.voice.asr4all.Asr4allModelFiles.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Asr4allModelFilesTest {
    @Test
    fun sizesHaveSixDistinctDownloadsWithNoSharedFileNames() {
        val s = Asr4allModelFiles.models(Size.S)
        val m = Asr4allModelFiles.models(Size.M)

        assertEquals(6, s.size)
        assertEquals(6, m.size)
        assertTrue(s.map { it.fileName }.none { it in m.map { f -> f.fileName } })
    }

    @Test
    fun eachSizeDownloadsFromItsPinnedRevision() {
        assertTrue(Asr4allModelFiles.models(Size.S).all { "futo-org/asr4all-s/resolve/87a6b5d093723fe37e9f69ad26307a3b95a31af0/onnx/" in it.downloadUrl })
        assertTrue(Asr4allModelFiles.models(Size.M).all { "futo-org/asr4all-m/resolve/7d6e9145109a5bece4f05831d046e93d27e38a99/onnx/" in it.downloadUrl })
    }

    @Test
    fun sharedWeightsReflectSizeOfEachModel() {
        val sWeights = Asr4allModelFiles.models(Size.S).single { it.fileName.endsWith(".onnx.data") }
        val mWeights = Asr4allModelFiles.models(Size.M).single { it.fileName.endsWith(".onnx.data") }

        assertEquals(108_249_312L, sWeights.approxSizeBytes)
        assertEquals(214_322_720L, mWeights.approxSizeBytes)
    }

    @Test
    fun defaultSizeKeepsNoArgumentModelsListOnS() {
        assertEquals(Asr4allModelFiles.models(Size.S), Asr4allModelFiles.models)
    }
}
