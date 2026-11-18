package com.kernel.ai.core.voice.asr4all

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption

/**
 * An initializer whose bytes live in the release's shared external-data file. Offsets come from
 * the graph's own protobuf records; the ONNX protobuf wire format is walked here for the few
 * fields needed, so no protobuf dependency is required. Port of `_external_tensors` in the
 * reference driver.
 */
internal data class Asr4allExternalTensor(
    val name: String,
    val onnxDataType: Int,
    val dims: LongArray,
    val offset: Long,
    val length: Long,
)

internal object Asr4allExternalData {
    private const val MODEL_GRAPH = 7
    private const val GRAPH_INITIALIZER = 5
    private const val TENSOR_DIMS = 1
    private const val TENSOR_DATA_TYPE = 2
    private const val TENSOR_NAME = 8
    private const val TENSOR_EXTERNAL_DATA = 13
    private const val TENSOR_DATA_LOCATION = 14
    private const val LOCATION_EXTERNAL = 1L

    /** Initializers of an ONNX model that are stored externally. */
    fun externalTensors(model: ByteArray): List<Asr4allExternalTensor> {
        val out = mutableListOf<Asr4allExternalTensor>()
        for (graph in fields(model)) {
            if (graph.number != MODEL_GRAPH || graph.bytes == null) continue
            for (tensor in fields(graph.bytes)) {
                if (tensor.number != GRAPH_INITIALIZER || tensor.bytes == null) continue
                parseTensor(tensor.bytes)?.let(out::add)
            }
        }
        return out
    }

    private fun parseTensor(bytes: ByteArray): Asr4allExternalTensor? {
        var name = ""
        var dataType = 0
        val dims = mutableListOf<Long>()
        val external = mutableMapOf<String, String>()
        var location = 0L
        for (f in fields(bytes)) {
            when (f.number) {
                TENSOR_DIMS -> if (f.bytes == null) {
                    dims += f.value
                } else {
                    var i = 0
                    while (i < f.bytes.size) {
                        val (d, next) = varint(f.bytes, i)
                        dims += d
                        i = next
                    }
                }
                TENSOR_DATA_TYPE -> dataType = f.value.toInt()
                TENSOR_NAME -> name = String(f.bytes!!, StandardCharsets.UTF_8)
                TENSOR_EXTERNAL_DATA -> {
                    val entry = fields(f.bytes!!).associate { it.number to String(it.bytes!!, StandardCharsets.UTF_8) }
                    external[entry[1]!!] = entry[2]!!
                }
                TENSOR_DATA_LOCATION -> location = f.value
            }
        }
        if (location != LOCATION_EXTERNAL) return null
        return Asr4allExternalTensor(
            name = name,
            onnxDataType = dataType,
            dims = dims.toLongArray(),
            offset = external["offset"]?.toLong() ?: 0L,
            length = external.getValue("length").toLong(),
        )
    }

    /** Maps [dataFile] once, read-only. Every initializer is a [slice] of this single mapping. */
    fun mapWeights(dataFile: File): ByteBuffer =
        FileChannel.open(dataFile.toPath(), StandardOpenOption.READ).use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
        }

    /** Little-endian view of `[offset, offset + length)` of [mapped]. Shares the mapping, copies nothing. */
    fun slice(mapped: ByteBuffer, offset: Long, length: Long): ByteBuffer {
        val view = mapped.duplicate()
        view.limit((offset + length).toInt())
        view.position(offset.toInt())
        return view.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private class Field(val number: Int, val value: Long, val bytes: ByteArray?)

    /** Top-level fields of one protobuf message. Varints carry [Field.value]; length-delimited carry [Field.bytes]. */
    private fun fields(buf: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var i = 0
        while (i < buf.size) {
            val (key, afterKey) = varint(buf, i)
            i = afterKey
            val number = (key shr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> {
                    val (v, next) = varint(buf, i)
                    out += Field(number, v, null)
                    i = next
                }
                2 -> {
                    val (n, next) = varint(buf, i)
                    val end = next + n.toInt()
                    out += Field(number, 0L, buf.copyOfRange(next, end))
                    i = end
                }
                1 -> i += 8
                5 -> i += 4
                else -> error("unsupported protobuf wire type ${key and 7L}")
            }
        }
        return out
    }

    private fun varint(buf: ByteArray, start: Int): Pair<Long, Int> {
        var shift = 0
        var value = 0L
        var i = start
        while (true) {
            val b = buf[i++].toInt()
            value = value or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return value to i
            shift += 7
        }
    }
}
