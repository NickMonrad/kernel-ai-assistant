package ai.jandal.asr4alltier

import android.os.Process
import java.io.File

/**
 * Low-end proxy support: restricts every thread of this (instrumentation) process to a CPU mask with the
 * device's `taskset -a -p <mask> <pid>` (toybox), run as this app's own uid. Threads created later inherit the
 * creating thread's mask. Android can reset affinity when a process changes cpuset, so callers re-apply before
 * each clip and [allowedLists] records what every thread actually had.
 */
object CpuAffinity {
    fun apply(maskHex: String) {
        val proc = ProcessBuilder("taskset", "-a", "-p", maskHex, Process.myPid().toString())
            .redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "taskset failed: $out" }
    }

    /** Distinct `Cpus_allowed_list` values across this process's threads. */
    fun allowedLists(): Set<String> =
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
            runCatching {
                File(task, "status").readLines().first { it.startsWith("Cpus_allowed_list:") }
                    .substringAfter(':').trim()
            }.getOrNull()
        }.toSet()
}
