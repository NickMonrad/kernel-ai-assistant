package ai.jandal.asr4alltier

import org.pytorch.executorch.BackendOptionsMap
import org.pytorch.executorch.Module

/** ExecuTorch module loading with explicit XNNPACK runtime options (see #1988 root cause). */
object EtModules {
    const val XNNPACK = "XnnpackBackend"

    /**
     * Variant tokens joined with '+': `default`, `wc0`/`wc1` (XNNPACK weight cache), `ws0`/`ws1`/`ws2`
     * (workspace sharing Disabled/PerModel/Global), `t<N>` (thread count; 0 = runtime default), `file`.
     */
    fun load(path: String, variant: String): Module {
        val options = BackendOptionsMap()
        var threads = 0
        var loadMode = Module.LOAD_MODE_MMAP
        for (token in variant.split('+').filter { it.isNotEmpty() && it != "default" }) when {
            token.startsWith("wc") -> options.setInt(XNNPACK, "weight_cache_enabled", token.drop(2).toInt())
            token.startsWith("ws") -> options.setInt(XNNPACK, "workspace_sharing_mode", token.drop(2).toInt())
            token.startsWith("t") -> threads = token.drop(1).toInt()
            token == "file" -> loadMode = Module.LOAD_MODE_FILE
            else -> error("unknown variant token $token")
        }
        return if (options.isEmpty()) Module.load(path, loadMode, threads)
        else Module.load(path, options, loadMode, threads)
    }
}
