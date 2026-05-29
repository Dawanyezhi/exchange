package com.laser.exchange.matching.snapshot;

import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import lombok.extern.slf4j.Slf4j;
import org.agrona.DirectBuffer;

import java.util.concurrent.locks.LockSupport;

/**
 * Aeron {@link ExclusivePublication} 适配器：把 SBE 消息 offer 到 Aeron。
 *
 * <p>用于生产路径下向 Aeron Cluster 的 snapshot publication 写快照。
 */
@Slf4j
public class AeronPublicationSnapshotWriter implements SnapshotWriter {

    private final ExclusivePublication publication;

    public AeronPublicationSnapshotWriter(ExclusivePublication publication) {
        this.publication = publication;
    }

    @Override
    public void write(DirectBuffer buffer, int offset, int length) {
        long result;
        int spins = 0;
        while ((result = publication.offer(buffer, offset, length)) < 0L) {
            if (result == Publication.CLOSED) {
                throw new IllegalStateException("snapshot publication closed");
            }
            if (result == Publication.MAX_POSITION_EXCEEDED) {
                throw new IllegalStateException("snapshot publication max position exceeded");
            }
            if (++spins > 1024) {
                LockSupport.parkNanos(1L);
                spins = 0;
            } else {
                Thread.onSpinWait();
            }
        }
    }
}
