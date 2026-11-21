package com.kernel.ai.core.voice.asr4all

import com.kernel.ai.core.executorch.TensorDType
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Declared dtype and sizes of one method input or output, read from the `.pte` file. */
data class PteTensorInfo(
    /** Null for an element type the engine does not use. */
    val dtype: TensorDType?,
    val sizes: List<Long>,
    /** Sizes are upper bounds rather than exact (the tensor's shape is dynamic). */
    val dynamic: Boolean,
) {
    override fun toString(): String = "${dtype ?: "?"}$sizes${if (dynamic) " (dynamic)" else ""}"
}

/** Declared inputs and outputs of one method. A null entry is a non-tensor value. */
data class PteMethodInfo(val inputs: List<PteTensorInfo?>, val outputs: List<PteTensorInfo?>)

/**
 * Reads the declared input and output tensors of every method from a `.pte` file, without the
 * runtime.
 *
 * Why: the ExecuTorch 1.5.1 Android runtime aborts the process when a method receives inputs
 * whose shapes the program rejects (docs/architecture/executorch-runtime.md), so a `.pte` that
 * does not match the config files (for example L's program next to M's `metadata.json`) must be
 * caught before any method runs. ExecuTorch's Java API has no method metadata, so this reads
 * the program flatbuffer directly, following ExecuTorch's public schema (`schema/program.fbs`
 * at v1.5.1: Program.execution_plan → ExecutionPlan.{name, values, inputs, outputs} →
 * EValue.val → Tensor.{scalar_type, sizes, shape_dynamism}). Only these metadata fields are
 * read; instructions, delegates and constant data are not.
 */
object PteMethodTable {
    /** @throws Asr4AllConfigException if [file] is not a readable ExecuTorch program. */
    fun read(file: File): Map<String, PteMethodInfo> = try {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val programSize = programBytes(channel, raf.length())
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, programSize).order(ByteOrder.LITTLE_ENDIAN)
            parse(buffer)
        }
    } catch (e: Asr4AllConfigException) {
        throw e
    } catch (e: IOException) {
        throw Asr4AllConfigException("cannot read ${file.path}: ${e.message}", e)
    } catch (e: RuntimeException) {
        // Offsets pointing outside the buffer: a malformed or truncated program.
        throw Asr4AllConfigException("${file.path} is not a well-formed ExecuTorch program: $e", e)
    }

    /** Size of the flatbuffer: the extended header's program size, or the whole file without one. */
    private fun programBytes(channel: FileChannel, fileLength: Long): Long {
        val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        channel.read(head, 0)
        if (head.position() < 8) throw Asr4AllConfigException("ExecuTorch program is only $fileLength bytes")
        val identifier = String(head.array(), 4, 4, Charsets.US_ASCII)
        if (identifier != PROGRAM_IDENTIFIER) {
            throw Asr4AllConfigException("not an ExecuTorch $PROGRAM_IDENTIFIER program (identifier '$identifier')")
        }
        if (head.position() < 24 || String(head.array(), 8, 4, Charsets.US_ASCII) != EXTENDED_MAGIC) return fileLength
        val programSize = head.getLong(16)
        if (programSize <= 0 || programSize > fileLength) {
            throw Asr4AllConfigException("ExecuTorch program declares $programSize program bytes in a $fileLength-byte file")
        }
        return programSize
    }

    internal fun parse(buffer: ByteBuffer): Map<String, PteMethodInfo> {
        val fb = FlatBuffer(buffer)
        val program = fb.root()
        val result = LinkedHashMap<String, PteMethodInfo>()
        for (plan in fb.tables(program, PROGRAM_EXECUTION_PLAN)) {
            val name = fb.string(plan, PLAN_NAME) ?: continue
            val values = fb.tables(plan, PLAN_VALUES)
            fun tensor(index: Int): PteTensorInfo? {
                val value = values.getOrNull(index) ?: throw Asr4AllConfigException("method $name refers to value $index of ${values.size}")
                if (fb.ubyte(value, EVALUE_TYPE) != KERNEL_TYPES_TENSOR) return null
                val t = fb.table(value, EVALUE_VAL) ?: return null
                return PteTensorInfo(
                    dtype = SCALAR_TYPES[fb.byte(t, TENSOR_SCALAR_TYPE)],
                    sizes = fb.ints(t, TENSOR_SIZES).map(Int::toLong),
                    dynamic = fb.byte(t, TENSOR_SHAPE_DYNAMISM).let {
                        if (it == DYNAMISM_DYNAMIC_UNBOUND) throw Asr4AllConfigException("program has an unbounded dynamic tensor; the asr4all exports are static or bounded")
                        it != 0
                    },
                )
            }
            result[name] = PteMethodInfo(
                inputs = fb.ints(plan, PLAN_INPUTS).map(::tensor),
                outputs = fb.ints(plan, PLAN_OUTPUTS).map(::tensor),
            )
        }
        if (result.isEmpty()) throw Asr4AllConfigException("ExecuTorch program has no methods")
        return result
    }

    private const val PROGRAM_IDENTIFIER = "ET12"
    private const val EXTENDED_MAGIC = "eh00"

    // Field slots, in declaration order in schema/program.fbs (a union takes two: type, value).
    private const val PROGRAM_EXECUTION_PLAN = 1
    private const val PLAN_NAME = 0
    private const val PLAN_VALUES = 2
    private const val PLAN_INPUTS = 3
    private const val PLAN_OUTPUTS = 4
    private const val EVALUE_TYPE = 0
    private const val EVALUE_VAL = 1
    private const val KERNEL_TYPES_TENSOR = 5 // NONE, Null, Int, Bool, Double, Tensor, ...
    private const val TENSOR_SCALAR_TYPE = 0
    private const val TENSOR_SIZES = 2
    private const val TENSOR_SHAPE_DYNAMISM = 8
    private const val DYNAMISM_DYNAMIC_UNBOUND = 2 // schema/program.fbs TensorShapeDynamism: STATIC 0, DYNAMIC_BOUND 1, DYNAMIC_UNBOUND 2

    /** `schema/scalar_type.fbs` codes for the element types `:core:executorch` supports. */
    private val SCALAR_TYPES = mapOf(
        0 to TensorDType.UINT8,
        1 to TensorDType.INT8,
        3 to TensorDType.INT32,
        4 to TensorDType.INT64,
        6 to TensorDType.FLOAT32,
    )
}

/** Minimal little-endian flatbuffer reader: tables, vectors of tables/ints, strings, scalars. */
private class FlatBuffer(private val b: ByteBuffer) {
    fun root(): Int = b.getInt(0)

    /** Absolute position of [table]'s field [slot], or 0 if absent. */
    private fun field(table: Int, slot: Int): Int {
        val vtable = table - b.getInt(table)
        val vtableLength = b.getShort(vtable).toInt() and 0xffff
        val entry = 4 + 2 * slot
        if (entry >= vtableLength) return 0
        val offset = b.getShort(vtable + entry).toInt() and 0xffff
        return if (offset == 0) 0 else table + offset
    }

    private fun deref(position: Int): Int = position + b.getInt(position)

    fun table(table: Int, slot: Int): Int? = field(table, slot).takeIf { it != 0 }?.let(::deref)

    fun byte(table: Int, slot: Int): Int = field(table, slot).let { if (it == 0) 0 else b.get(it).toInt() }

    fun ubyte(table: Int, slot: Int): Int = byte(table, slot) and 0xff

    fun string(table: Int, slot: Int): String? {
        val s = table(table, slot) ?: return null
        val length = count(s, 1)
        val bytes = ByteArray(length) { b.get(s + 4 + it) }
        return String(bytes, Charsets.UTF_8)
    }

    fun ints(table: Int, slot: Int): List<Int> {
        val v = table(table, slot) ?: return emptyList()
        return List(count(v, 4)) { b.getInt(v + 4 + 4 * it) }
    }

    fun tables(table: Int, slot: Int): List<Int> {
        val v = table(table, slot) ?: return emptyList()
        return List(count(v, 4)) { deref(v + 4 + 4 * it) }
    }

    /**
     * Element count of the vector whose length prefix is at [position]. Checked against the bytes
     * left in the buffer before anything is allocated, so a corrupted count fails typed.
     */
    private fun count(position: Int, elementSize: Int): Int {
        val n = b.getInt(position)
        val room = (b.limit() - (position + 4)).toLong()
        if (n < 0 || n.toLong() * elementSize > room) {
            throw Asr4AllConfigException("vector at offset $position claims $n elements; only $room bytes follow it")
        }
        return n
    }
}
