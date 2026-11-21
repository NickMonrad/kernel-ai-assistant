package com.kernel.ai.core.voice.asr4all

/** Typed failures of the asr4all engine. None of them ever carries partial text. */
sealed class Asr4AllException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The model files disagree with each other, with the requested size or tier, or with the
 * program's methods (for example `metadata.json` from a different model size).
 */
class Asr4AllConfigException(message: String, cause: Throwable? = null) : Asr4AllException(message, cause)

/**
 * A method returned tensors whose shape or dtype differ from the layout derived from the
 * config files. Raised before any output buffer is indexed.
 */
class Asr4AllShapeException(
    /** The method whose output did not match. */
    val method: String,
    /** Names the output index and both shapes (from `:core:executorch`'s check). */
    message: String,
    cause: Throwable? = null,
) : Asr4AllException(message, cause)

/** The runtime failed to execute a method. */
class Asr4AllRuntimeException(message: String, cause: Throwable? = null) : Asr4AllException(message, cause)

/** The engine was used after [Asr4AllEngine.close]. */
class Asr4AllClosedException : Asr4AllException("asr4all engine is closed")

/** An input chunk was not usable (for example a non-finite sample). */
class Asr4AllInputException(message: String) : Asr4AllException(message)
