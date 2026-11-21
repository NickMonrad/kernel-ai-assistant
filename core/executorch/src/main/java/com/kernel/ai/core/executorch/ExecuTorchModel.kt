package com.kernel.ai.core.executorch

import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How the runtime reads the `.pte` file (values match `org.pytorch.executorch.Module`). */
enum class LoadMode(internal val nativeCode: Int) {
    /** Read the whole file into memory. */
    FILE(0),

    /** Map the file; pages load on demand. Avoids a heap-sized copy of large programs. */
    MMAP(1),
}

/**
 * A loaded ExecuTorch program (`.pte`).
 *
 * Threading: every call blocks, so call from a background dispatcher, never the main thread.
 * Calls are serialised per model; [close] waits for an in-flight [run] to return. After
 * [close], [run] throws [ExecuTorchClosedException]; it never reaches freed native memory.
 * A model that is never closed keeps its thread-pool slot until the process exits.
 *
 * Thread pool: [threads] sizes ExecuTorch's single, process-wide XNNPACK pool. While any model
 * is open, every load must use the same count; a different count throws before loading,
 * because resizing the pool would free the pool the open models' delegates still use.
 *
 * Native failures: the ExecuTorch 1.5.1 AAR aborts the process instead of throwing when a load
 * or method fails, so this class rejects what it can before native code: unreadable or
 * truncated `.pte` files, unknown methods and inputs that do not match the [MethodSignature].
 * A program whose real shapes differ from the declared signature still aborts the process.
 */
class ExecuTorchModel private constructor(
    /** Absolute path of the loaded `.pte`. */
    val path: String,
    /** XNNPACK thread-pool size requested at load. */
    val threads: Int,
    /** Methods the program exports, e.g. `forward`. */
    val methodNames: Set<String>,
    private val native: NativeModule,
) : Closeable {
    private val lock = ReentrantLock()
    private var closed = false // Guarded by lock.

    val isClosed: Boolean get() = lock.withLock { closed }

    /**
     * Runs [signature]'s method. Checks [inputs] against [MethodSignature.inputs] before calling
     * the runtime and every output against [MethodSignature.outputs] before returning them.
     *
     * @throws ExecuTorchClosedException if the model is closed (checked first).
     * @throws IllegalArgumentException if the program has no such method.
     * @throws InputShapeMismatchException if [inputs] differ from the signature.
     * @throws OutputShapeMismatchException if the outputs differ from the signature.
     * @throws ExecuTorchException if the runtime fails, or an output has a dtype this module
     *   does not support.
     */
    fun run(signature: MethodSignature, inputs: List<ExecuTorchTensor>): List<ExecuTorchTensor> =
        lock.withLock {
            if (closed) throw ExecuTorchClosedException(path)
            val method = signature.method
            require(method in methodNames) {
                "ExecuTorch program $path has no method '$method'; it exports $methodNames"
            }
            requireInputShapes(method, inputs, signature.inputs)
            val outputs = native.execute(method, inputs, signature.outputs)
            assertOutputShapes(method, outputs, signature.outputs)
            outputs
        }

    /** Frees the native program. Idempotent; blocks until an in-flight [run] returns. */
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            try {
                native.destroy()
            } finally {
                ExecuTorchThreadPool.released()
            }
        }
    }

    companion object {
        /**
         * Loads [file] with an explicit XNNPACK thread count.
         *
         * @param threads XNNPACK pool size, at least 1. There is no "auto" value: the runtime's
         *   default (half the cores, including efficiency cores) is a device-dependent guess.
         * @throws IllegalArgumentException if [threads] < 1 or [file] is not a readable file.
         * @throws IllegalStateException if open models use a different thread count.
         * @throws ExecuTorchException if [file] is not a complete program for this runtime, or
         *   the native runtime is unavailable on this ABI.
         */
        fun load(file: File, threads: Int, loadMode: LoadMode = LoadMode.MMAP): ExecuTorchModel =
            load(file, threads, loadMode, PyTorchModuleLoader)

        internal fun load(
            file: File,
            threads: Int,
            loadMode: LoadMode,
            loader: NativeModuleLoader,
        ): ExecuTorchModel {
            require(threads >= 1) { "ExecuTorch thread count must be >= 1, got $threads" }
            require(file.isFile && file.canRead()) { "ExecuTorch program ${file.path} is not a readable file" }
            val path = file.absolutePath
            PteHeader.check(file)
            return ExecuTorchThreadPool.load(threads) {
                val native = loader.load(path, loadMode, threads)
                val methods = try {
                    native.methodNames()
                } catch (e: Throwable) {
                    native.destroy()
                    throw e
                }
                ExecuTorchModel(path, threads, methods, native)
            }
        }
    }
}
