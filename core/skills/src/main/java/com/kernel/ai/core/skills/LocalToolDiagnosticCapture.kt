package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/** Narrow host-facing capture control that does not expose LiteRT-LM's ToolSet supertype. */
@Singleton
class LocalToolDiagnosticCapture @Inject constructor(
    private val toolSet: KernelAIToolSet,
) {
    fun begin() = toolSet.beginLocalDiagnosticCapture()

    fun finish(): LocalToolDiagnosticSnapshot = toolSet.finishLocalDiagnosticCapture()
}
