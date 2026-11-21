package ai.jandal.spike1988

import android.os.Debug
import java.io.File

/** Process memory: PSS via [Debug.getPss] (sampled) and kernel-tracked peak RSS (VmHWM, exact). */
class MemorySampler(private val periodMs: Long = 250) : AutoCloseable {
    @Volatile private var running = true
    @Volatile var peakPssKb = 0L; private set
    private val thread = Thread({
        while (running) {
            sample()
            try { Thread.sleep(periodMs) } catch (_: InterruptedException) { return@Thread }
        }
    }, "pss-sampler").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }

    fun start(): MemorySampler { thread.start(); return this }

    fun sample(): Long = Debug.getPss().also { if (it > peakPssKb) peakPssKb = it }

    override fun close() { running = false; thread.interrupt(); thread.join(2_000) }

    companion object {
        fun status(field: String): Long =
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("$field:") }
                ?.split(Regex("\\s+"))?.get(1)?.toLong() ?: -1
    }
}
