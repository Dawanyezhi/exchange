package com.laser.exchange.matching.snapshot;

import io.aeron.logbuffer.FragmentHandler;

/**
 * 快照读入抽象，与 {@link SnapshotWriter} 对偶。
 *
 * <p>实现负责从底层 (Aeron Image / ByteBuffer / File) 提供一条一条的 SBE 消息给 handler。
 */
public interface SnapshotReader {

    /**
     * 读取一条消息交给 handler。
     *
     * @return true 成功读取一条；false 已读完（EOF）
     */
    boolean readOne(FragmentHandler handler);
}
