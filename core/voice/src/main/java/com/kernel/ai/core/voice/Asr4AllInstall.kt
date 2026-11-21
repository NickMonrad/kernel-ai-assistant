package com.kernel.ai.core.voice

import android.content.Context
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.isDownloaded
import com.kernel.ai.core.inference.download.localFile
import com.kernel.ai.core.inference.hardware.HardwareTier
import com.kernel.ai.core.voice.asr4all.Asr4AllEngine
import com.kernel.ai.core.voice.asr4all.Asr4AllSize
import java.io.File

/**
 * On-device layout and tier policy of the shipped asr4all exports (M and L; S is not shipped).
 *
 * Each size's files live under `models/asr4all/<size>/`, the directory tree
 * [Asr4AllEngine.open] reads, so the engine opens that directory directly. Deleting a size
 * removes only its own directory.
 */
object Asr4AllInstall {
    /** Size used until the user chooses another; the same on every tier (#2013). */
    val defaultSize: Asr4AllSize = Asr4AllSize.M

    /** Sizes the user may choose on [tier]. L is not offered on LOW_POWER (#2013). */
    fun selectableSizes(tier: HardwareTier): List<Asr4AllSize> =
        if (tier == HardwareTier.LOW_POWER) listOf(Asr4AllSize.M) else listOf(Asr4AllSize.M, Asr4AllSize.L)

    /** The stored choice when [tier] offers it, else [defaultSize]. */
    fun effectiveSize(stored: Asr4AllSize, tier: HardwareTier): Asr4AllSize =
        if (stored in selectableSizes(tier)) stored else defaultSize

    /** ExecuTorch thread count: 2 on LOW_POWER, the engine default otherwise (#2013). */
    fun threadsFor(tier: HardwareTier): Int =
        if (tier == HardwareTier.LOW_POWER) 2 else Asr4AllEngine.DEFAULT_THREADS

    /** Every file of [size]. */
    fun models(size: Asr4AllSize): List<KernelModel> = when (size) {
        Asr4AllSize.M -> listOf(
            KernelModel.ASR4ALL_M_CONFIG,
            KernelModel.ASR4ALL_M_RELEASE_GEOM,
            KernelModel.ASR4ALL_M_PREPROCESSOR,
            KernelModel.ASR4ALL_M_METADATA,
            KernelModel.ASR4ALL_M_ENCODER,
        )
        Asr4AllSize.L -> listOf(
            KernelModel.ASR4ALL_L_CONFIG,
            KernelModel.ASR4ALL_L_RELEASE_GEOM,
            KernelModel.ASR4ALL_L_PREPROCESSOR,
            KernelModel.ASR4ALL_L_METADATA,
            KernelModel.ASR4ALL_L_ENCODER,
        )
        Asr4AllSize.S -> throw IllegalArgumentException("asr4all S is not shipped")
    }

    /** The engine model directory of [size]: the one holding its `config.json`. */
    fun root(context: Context, size: Asr4AllSize): File =
        models(size).first().localFile(context).parentFile!!

    fun isDownloaded(context: Context, size: Asr4AllSize): Boolean =
        models(size).all { it.isDownloaded(context) }

    /** Removes every file of [size], including partial downloads. Other sizes are untouched. */
    fun deleteFiles(context: Context, size: Asr4AllSize) {
        root(context, size).deleteRecursively()
    }
}
