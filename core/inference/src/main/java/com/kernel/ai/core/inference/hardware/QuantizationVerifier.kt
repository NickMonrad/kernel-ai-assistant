package com.kernel.ai.core.inference.hardware

import android.util.Log
import java.io.File

private const val TAG = "QuantizationVerifier"

/**
 * Guards against accidentally loading an FP32 model that would exhaust device RAM.
 *
 * A correctly quantized `.litertlm` file (INT4 or INT8) should be within a
 * reasonable range of its expected download size. An FP32 model would be
 * approximately 4× larger than an INT8 equivalent.
 *
 * This is a best-effort check based on file size — not a true format inspection.
 */
object QuantizationVerifier {

    /**
     * Returns true if the model file passes the size sanity check.
     *
     * @param modelFile       The downloaded `.litertlm` file.
     * @param expectedBytes   The expected file size from [KernelModel.approxSizeBytes].
     * @param toleranceFactor How much larger (multiplier) the file may be before
     *                        flagging as suspicious. Default 1.5× (50% slack).
     */
    fun verify(modelFile: File, expectedBytes: Long, toleranceFactor: Float = 1.5f): Boolean {
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
            return false
        }

        val actualBytes = modelFile.length()
        val maxAllowed = (expectedBytes * toleranceFactor).toLong()

        return if (actualBytes > maxAllowed) {
            Log.e(
                TAG,
                "⚠️ Model size check FAILED for ${modelFile.name}: " +
                    "actual=${formatMb(actualBytes)} MB, " +
                    "expected≈${formatMb(expectedBytes)} MB, " +
                    "threshold=${formatMb(maxAllowed)} MB. " +
                    "Model may be FP32 — this will likely OOM the device.",
            )
            false
        } else {
            Log.i(
                TAG,
                "Model size check OK for ${modelFile.name}: " +
                    "${formatMb(actualBytes)} MB (expected≈${formatMb(expectedBytes)} MB)",
            )
            true
        }
    }

    private fun formatMb(bytes: Long): String = "%.0f".format(bytes / (1024.0 * 1024.0))
}
