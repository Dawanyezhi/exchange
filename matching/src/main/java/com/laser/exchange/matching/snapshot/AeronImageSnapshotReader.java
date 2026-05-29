package com.laser.exchange.matching.snapshot;

import io.aeron.Image;
import io.aeron.logbuffer.FragmentHandler;

/**
 * Aeron {@link Image} 适配器：从 Aeron snapshot image 逐条拉取消息。
 *
 * <p>用于生产路径下从 ClusterSnapshot image 恢复状态。
 */
public class AeronImageSnapshotReader implements SnapshotReader {

    private final Image image;

    public AeronImageSnapshotReader(Image image) {
        this.image = image;
    }

    @Override
    public boolean readOne(FragmentHandler handler) {
        // poll 返回读取的 fragment 数；快照每条消息一个 fragment
        int fragments = image.poll(handler, 1);
        if (fragments > 0) {
            return true;
        }
        // 已到 EOF（Aeron Image 读完后 isEndOfStream=true）
        return !image.isEndOfStream();
    }
}
