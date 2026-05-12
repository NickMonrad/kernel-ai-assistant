package com.kernel.ai.core.voice

import java.io.File

internal const val SHERPA_MODEL_ONNX_FILE = "model.onnx"
internal const val SHERPA_MODEL_JSON_FILE = "model.onnx.json"
internal const val SHERPA_TOKENS_FILE = "tokens.txt"
internal const val SHERPA_ESPEAK_DATA_DIR = "espeak-ng-data"

/** voices.bin required by the Kokoro model (speaker embeddings). */
internal const val SHERPA_KOKORO_VOICES_FILE = "voices.bin"

internal fun hasRequiredSherpaVoicePackFiles(dir: File): Boolean =
    dir.isDirectory &&
        File(dir, SHERPA_MODEL_ONNX_FILE).isFile &&
        File(dir, SHERPA_TOKENS_FILE).isFile &&
        File(dir, SHERPA_ESPEAK_DATA_DIR).isDirectory

/**
 * Validates that the extracted Kokoro voice pack directory contains all required files:
 * `model.onnx`, `tokens.txt`, `voices.bin`, and the `espeak-ng-data` directory.
 */
internal fun hasRequiredKokoroVoicePackFiles(dir: File): Boolean =
    dir.isDirectory &&
        File(dir, SHERPA_MODEL_ONNX_FILE).isFile &&
        File(dir, SHERPA_TOKENS_FILE).isFile &&
        File(dir, SHERPA_KOKORO_VOICES_FILE).isFile &&
        File(dir, SHERPA_ESPEAK_DATA_DIR).isDirectory

internal fun normalizeExtractedSherpaVoicePack(dir: File) {
    renameFirstMatch(
        dir = dir,
        targetName = SHERPA_MODEL_ONNX_FILE,
        candidates = dir.listFiles { file ->
            file.isFile &&
                file.extension == "onnx" &&
                file.name != SHERPA_MODEL_ONNX_FILE &&
                !file.name.endsWith(".onnx.json")
        }.orEmpty(),
    )
    renameFirstMatch(
        dir = dir,
        targetName = SHERPA_MODEL_JSON_FILE,
        candidates = dir.listFiles { file ->
            file.isFile &&
                file.name.endsWith(".onnx.json") &&
                file.name != SHERPA_MODEL_JSON_FILE
        }.orEmpty(),
    )
}

private fun renameFirstMatch(
    dir: File,
    targetName: String,
    candidates: Array<out File>,
) {
    val target = File(dir, targetName)
    if (target.exists()) return

    val source = candidates.firstOrNull() ?: return
    if (!source.renameTo(target)) {
        source.copyTo(target, overwrite = true)
        source.delete()
    }
}
