package com.kernel.ai.core.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.kernel.ai.core.inference.download.KernelModel
import com.kernel.ai.core.inference.download.localFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EmbeddingEngine backed by MediaPipe TextEmbedder running the Universal Sentence Encoder
 * (or any compatible .tflite model placed at [KernelModel.UNIVERSAL_SENTENCE_ENCODER.localFile]).
 *
 * - 512-dimensional L2-normalised float embeddings
 * - Model file (~6 MB) is downloaded by [ModelDownloadManager] on first use
 * - Returns empty array from [embed] if the model file is not yet present
 */
@Singleton
class MediaPipeEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : EmbeddingEngine {

    override val dimensions: Int = 512

    private var embedder: TextEmbedder? = null
    private val lock = Any()

    private fun ensureEmbedder(): TextEmbedder? {
        synchronized(lock) {
            if (embedder != null) return embedder
            val modelFile = KernelModel.UNIVERSAL_SENTENCE_ENCODER.localFile(context)
            if (!modelFile.exists()) {
                Log.w(TAG, "USE model not yet downloaded — embeddings unavailable")
                return null
            }
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelFile.absolutePath)
                        .build()
                )
                .setL2Normalize(true)
                .setQuantize(false)
                .build()
            embedder = TextEmbedder.createFromOptions(context, options)
            Log.i(TAG, "TextEmbedder ready (USE, ${dimensions}d)")
            return embedder
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val e = ensureEmbedder() ?: return@withContext FloatArray(0)
        val result = e.embed(text)
        result.embeddingResult().embeddings().firstOrNull()?.floatEmbedding() ?: FloatArray(0)
    }

    override fun close() {
        synchronized(lock) {
            embedder?.close()
            embedder = null
        }
    }

    companion object {
        private const val TAG = "MediaPipeEmbeddingEngine"
    }
}
