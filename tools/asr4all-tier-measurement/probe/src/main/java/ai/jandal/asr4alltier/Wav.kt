package ai.jandal.asr4alltier

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    /** 16 kHz mono PCM16 WAV -> float32 in [-1, 1), scaled exactly like the reference (`/ 32768`). */
    fun readPcm16Mono16k(file: File): FloatArray {
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && buf.getInt(0) == 0x46464952 && buf.getInt(8) == 0x45564157) { "not RIFF/WAVE" }
        var offset = 12
        var fmtOk = false
        while (offset + 8 <= bytes.size) {
            val id = buf.getInt(offset)
            val len = buf.getInt(offset + 4)
            require(len >= 0 && offset + 8L + len <= bytes.size) { "malformed chunk" }
            when (id) {
                0x20746d66 -> {
                    val format = buf.getShort(offset + 8).toInt()
                    val channels = buf.getShort(offset + 10).toInt()
                    val rate = buf.getInt(offset + 12)
                    val bits = buf.getShort(offset + 22).toInt()
                    require(format == 1 && channels == 1 && rate == 16_000 && bits == 16) {
                        "want 16 kHz mono PCM16, got format=$format ch=$channels rate=$rate bits=$bits"
                    }
                    fmtOk = true
                }
                0x61746164 -> {
                    require(fmtOk) { "data before fmt" }
                    return FloatArray(len / 2) { buf.getShort(offset + 8 + 2 * it) / 32768f }
                }
            }
            offset += 8 + len + (len and 1)
        }
        error("no data chunk")
    }
}
