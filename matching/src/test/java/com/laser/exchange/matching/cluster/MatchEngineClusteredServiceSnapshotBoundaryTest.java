package com.laser.exchange.matching.cluster;

import com.laser.exchange.matching.core.engine.MatchEngine;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import com.laser.exchange.matching.resultLog.ArchiveResultLogWriter;
import com.laser.exchange.matching.snapshot.SnapshotManager;
import com.laser.exchange.matching.snapshot.SnapshotWriter;
import com.laser.exchange.matching.validation.SerialNumValidator;
import io.aeron.ExclusivePublication;
import io.aeron.cluster.service.Cluster;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchEngineClusteredServiceSnapshotBoundaryTest {

    private TestableMatchEngineClusteredService service;
    private MatchEngine matchEngine;
    private SerialNumValidator serialNumValidator;
    private MatchResultEventsHelper eventsHelper;
    private FakeArchiveResultLogWriter resultLogWriter;

    @BeforeEach
    void setUp() {
        service = new TestableMatchEngineClusteredService();
        matchEngine = new MatchEngine();
        serialNumValidator = new SerialNumValidator(true);
        eventsHelper = new MatchResultEventsHelper();
        resultLogWriter = new FakeArchiveResultLogWriter(service.orderRecorder);

        ReflectionTestUtils.setField(service, "matchEngine", matchEngine);
        ReflectionTestUtils.setField(service, "serialNumValidator", serialNumValidator);
        ReflectionTestUtils.setField(service, "eventsHelper", eventsHelper);
        ReflectionTestUtils.setField(service, "resultLogWriter", resultLogWriter);
        ReflectionTestUtils.setField(service, "cluster", new FakeCluster());
        ReflectionTestUtils.setField(service, "snapshotManager", new SnapshotManager(matchEngine.getDefaultMarketOrderProtectionBps()));
    }

    @Test
    @DisplayName("无已 offer result 时直接写快照，不等待 Archive recording")
    void snapshotDoesNotWaitArchiveWhenNoResultHasBeenOffered() {
        resultLogWriter.lastOfferedResultSerialNum = 0L;

        service.onTakeSnapshot(null);

        assertEquals(0, resultLogWriter.awaitLastOfferedRecordedCount);
        assertTrue(service.snapshotWriteCount > 0);
    }

    @Test
    @DisplayName("快照 result 边界匹配时先等待 Archive recording 再写快照")
    void snapshotWaitsForArchiveRecordingBeforeWritingSnapshot() {
        eventsHelper.restoreNextResultSerialNum(11L);
        resultLogWriter.lastOfferedResultSerialNum = 10L;

        service.onTakeSnapshot(null);

        assertEquals(1, resultLogWriter.awaitLastOfferedRecordedCount);
        assertTrue(service.snapshotWriteCount > 0);
        assertTrue(resultLogWriter.firstAwaitOrder < service.firstSnapshotWriteOrder,
                "快照写入前必须先等待 Archive recording 追上已 offer 边界");
    }

    @Test
    @DisplayName("快照 result 边界不匹配时失败且不写快照")
    void snapshotFailsWhenResultBoundaryDoesNotMatchLastOfferedResult() {
        eventsHelper.restoreNextResultSerialNum(11L);
        resultLogWriter.lastOfferedResultSerialNum = 9L;

        assertThrows(IllegalStateException.class, () -> service.onTakeSnapshot(null));

        assertEquals(0, resultLogWriter.awaitLastOfferedRecordedCount);
        assertEquals(0, service.snapshotWriteCount);
    }

    private static final class TestableMatchEngineClusteredService extends MatchEngineClusteredService {
        private final OrderRecorder orderRecorder = new OrderRecorder();
        private int snapshotWriteCount;
        private int firstSnapshotWriteOrder = -1;

        @Override
        SnapshotWriter createSnapshotWriter(ExclusivePublication snapshotPublication) {
            return (buffer, offset, length) -> {
                snapshotWriteCount++;
                if (firstSnapshotWriteOrder < 0) {
                    firstSnapshotWriteOrder = orderRecorder.next();
                }
            };
        }
    }

    private static final class FakeArchiveResultLogWriter extends ArchiveResultLogWriter {
        private final OrderRecorder orderRecorder;
        private long lastOfferedResultSerialNum;
        private int awaitLastOfferedRecordedCount;
        private int firstAwaitOrder = -1;

        private FakeArchiveResultLogWriter(OrderRecorder orderRecorder) {
            this.orderRecorder = orderRecorder;
        }

        @Override
        public long getLastOfferedResultSerialNum() {
            return lastOfferedResultSerialNum;
        }

        @Override
        public void awaitLastOfferedRecorded() {
            awaitLastOfferedRecordedCount++;
            if (firstAwaitOrder < 0) {
                firstAwaitOrder = orderRecorder.next();
            }
        }
    }

    private static final class OrderRecorder {
        private int order;

        private int next() {
            return ++order;
        }
    }

    private static final class FakeCluster implements Cluster {
        @Override
        public int memberId() {
            return 0;
        }

        @Override
        public Role role() {
            return Role.LEADER;
        }

        @Override
        public long logPosition() {
            return 0;
        }

        @Override
        public io.aeron.Aeron aeron() {
            return null;
        }

        @Override
        public io.aeron.cluster.service.ClusteredServiceContainer.Context context() {
            return null;
        }

        @Override
        public io.aeron.cluster.service.ClientSession getClientSession(long clusterSessionId) {
            return null;
        }

        @Override
        public java.util.Collection<io.aeron.cluster.service.ClientSession> clientSessions() {
            return java.util.List.of();
        }

        @Override
        public void forEachClientSession(java.util.function.Consumer<? super io.aeron.cluster.service.ClientSession> action) {
        }

        @Override
        public boolean closeClientSession(long clusterSessionId) {
            return false;
        }

        @Override
        public long time() {
            return 1_700_000_000_000L;
        }

        @Override
        public java.util.concurrent.TimeUnit timeUnit() {
            return java.util.concurrent.TimeUnit.MILLISECONDS;
        }

        @Override
        public boolean scheduleTimer(long correlationId, long deadline) {
            return false;
        }

        @Override
        public boolean cancelTimer(long correlationId) {
            return false;
        }

        @Override
        public long offer(DirectBuffer buffer, int offset, int length) {
            return 0;
        }

        @Override
        public long offer(io.aeron.DirectBufferVector[] vectors) {
            return 0;
        }

        @Override
        public long tryClaim(int length, io.aeron.logbuffer.BufferClaim bufferClaim) {
            return 0;
        }

        @Override
        public org.agrona.concurrent.IdleStrategy idleStrategy() {
            return null;
        }
    }
}
