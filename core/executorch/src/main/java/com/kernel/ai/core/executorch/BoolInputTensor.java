package com.kernel.ai.core.executorch;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.pytorch.executorch.DType;
import org.pytorch.executorch.Tensor;

/**
 * BOOL input for the ExecuTorch 1.5.1 Java API, which has no BOOL factory.
 *
 * Written in Java because the AAR's {@code Tensor(long[])} constructor is {@code internal} to Kotlin callers.
 * No native code: the runtime's JNI reads a Java tensor's dtype through the overridable {@link #dtype()} and its
 * bytes through {@link #getRawDataBuffer()}. One byte per element, 0 or 1, row-major.
 */
final class BoolInputTensor extends Tensor {
    private final ByteBuffer bytes;

    BoolInputTensor(long[] shape, boolean[] values) {
        super(shape);
        bytes = ByteBuffer.allocateDirect(values.length).order(ByteOrder.nativeOrder());
        for (boolean value : values) {
            bytes.put(value ? (byte) 1 : (byte) 0);
        }
        bytes.rewind();
    }

    @Override
    public DType dtype() {
        return DType.BOOL;
    }

    @Override
    public Buffer getRawDataBuffer() {
        return bytes;
    }
}
