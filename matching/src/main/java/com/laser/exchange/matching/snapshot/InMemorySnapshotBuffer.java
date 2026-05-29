package com.laser.exchange.matching.snapshot;

import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存快照 sink/source，用于单元测试和 deterministic replay。
 *
 * <p>take 阶段累积到 {@code List<byte[]>}；load 阶段逐条 pop 交给 FragmentHandler。
 */
public class InMemorySnapshotBuffer implements SnapshotWriter, SnapshotReader {

    private final List<byte[]> frames = new ArrayList<>();
    private int readIndex = 0;

    @Override
    public void write(DirectBuffer buffer, int offset, int length) {
        byte[] copy = new byte[length];
        buffer.getBytes(offset, copy);
        frames.add(copy);
    }

    @Override
    public boolean readOne(FragmentHandler handler) {
        if (readIndex >= frames.size()) {
            return false;
        }
        byte[] bytes = frames.get(readIndex++);
        MutableDirectBuffer buf = new UnsafeBuffer(bytes);
        handler.onFragment(buf, 0, bytes.length, null);
        return true;
    }

    public int frameCount() {
        return frames.size();
    }

    public void reset() {
        readIndex = 0;
    }

    /** 仅供测试：篡改指定 frame 的一个字节，用于校验 checksum 失败路径。 */
    public void corruptByteForTest(int frameIndex, int byteIndex) {
        byte[] frame = frames.get(frameIndex);
        frame[byteIndex] = (byte) (frame[byteIndex] ^ 0x01);
    }

    /** 仅供测试：移除指定 frame，用于校验 entryCount/footer 失败路径。 */
    public void removeFrameForTest(int frameIndex) {
        frames.remove(frameIndex);
    }
}
