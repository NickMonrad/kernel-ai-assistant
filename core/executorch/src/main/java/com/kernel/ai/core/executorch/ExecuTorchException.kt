package com.kernel.ai.core.executorch

/**
 * The ExecuTorch runtime failed to load a program or run a method.
 *
 * @property errorCode ExecuTorch's runtime error code (`executorch::runtime::Error`), or
 *   null when the failure did not come from the runtime.
 */
class ExecuTorchException(
    message: String,
    val errorCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** [ExecuTorchModel] was used after [ExecuTorchModel.close]; nothing reached native code. */
class ExecuTorchClosedException(path: String) : IllegalStateException("ExecuTorch model $path is closed")
