package com.laser.exchange.matching.snapshot;

import org.agrona.DirectBuffer;

/**
 * 快照写出抽象：屏蔽底层 Aeron Publication / ByteBuffer / File 等多种落盘目的地。
 *
 * <p>SnapshotManager 生产 SBE 字节 → 调 writer.write(...) 一次代表一条消息；
 * 具体如何拷贝/offer 由实现决定。
 */
public interface SnapshotWriter {

    /**
     * 写入一条完整的 SBE message (含 header)。
     *
     * <p>实现需保证：
     * <ul>
     *   <li>写入顺序严格等于调用顺序 (单线程)</li>
     *   <li>返回时数据已交付给下一层（Aeron 缓冲、文件 fsync 等异步行为由实现自行处理）</li>
     * </ul>
     */
    void write(DirectBuffer buffer, int offset, int length);
}
