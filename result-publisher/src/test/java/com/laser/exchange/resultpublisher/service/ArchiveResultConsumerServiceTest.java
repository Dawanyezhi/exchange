package com.laser.exchange.resultpublisher.service;

import com.laser.exchange.resultpublisher.ResultFrameFixtures;
import com.laser.exchange.resultpublisher.archive.ResultLogEntry;
import com.laser.exchange.resultpublisher.checkpoint.InMemoryResultPublisherCheckpoint;
import com.laser.exchange.resultpublisher.checkpoint.ResultPublisherCheckpoint;
import com.laser.exchange.resultpublisher.config.ResultPublisherProperties;
import com.laser.exchange.resultpublisher.publish.ResultPublisher;
import io.aeron.Aeron;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveResultConsumerServiceTest {

    @Test
    @DisplayName("启动期达到最大退避仍未连接 Archive 时启动失败")
    void startupFailsWhenArchiveCannotBeConnectedBeforeMaxBackoff() {
        ResultPublisherProperties properties = new ResultPublisherProperties();
        properties.setRetryIntervalMs(1L);
        properties.setRetryMaxIntervalMs(1L);
        properties.setInitialConnectTimeoutMs(100L);
        properties.setAeronDriverTimeoutMs(1L);

        ArchiveResultConsumerService service = new FailingConnectArchiveResultConsumerService(properties);

        assertThrows(IllegalStateException.class, service::start);
        assertFalse(service.isRunning());
    }

    @Test
    @DisplayName("撮合结果推送成功后推进 checkpoint")
    void advancesCheckpointAfterPublishSucceeds() {
        ResultPublisherProperties properties = new ResultPublisherProperties();
        List<String> events = new ArrayList<>();
        RecordingPublisher publisher = new RecordingPublisher(events);
        RecordingCheckpoint checkpoint = new RecordingCheckpoint(events);
        ArchiveResultConsumerService service = new ArchiveResultConsumerService(properties, publisher, checkpoint);
        ResultLogEntry entry = resultLogEntry(3L, 256L, 384L);

        service.publishAndCheckpoint(entry);

        assertEquals(List.of("publish:3", "checkpoint:3"), events);
        assertEquals(384L, checkpoint.nextReplayPosition());
        assertEquals(3L, checkpoint.lastResultSerialNum());
    }

    @Test
    @DisplayName("撮合结果推送失败时不推进 checkpoint")
    void doesNotAdvanceCheckpointWhenPublishFails() {
        ResultPublisherProperties properties = new ResultPublisherProperties();
        List<String> events = new ArrayList<>();
        RecordingPublisher publisher = new RecordingPublisher(events);
        publisher.fail = true;
        RecordingCheckpoint checkpoint = new RecordingCheckpoint(events);
        ArchiveResultConsumerService service = new ArchiveResultConsumerService(properties, publisher, checkpoint);
        ResultLogEntry entry = resultLogEntry(3L, 256L, 384L);

        assertThrows(IllegalStateException.class, () -> service.publishAndCheckpoint(entry));

        assertEquals(List.of("publish:3"), events);
        assertEquals(0L, checkpoint.nextReplayPosition());
        assertEquals(0L, checkpoint.lastResultSerialNum());
    }

    private static ResultLogEntry resultLogEntry(long resultSerialNum, long startPosition, long endPosition) {
        return new ResultLogEntry(
                resultSerialNum,
                7L,
                startPosition,
                endPosition,
                1,
                1000L + resultSerialNum,
                1_700_000_000_000L + resultSerialNum,
                new byte[]{1, 2, 3},
                30817,
                ResultFrameFixtures.placeResult(resultSerialNum, 1000L + resultSerialNum)
        );
    }

    private static final class FailingConnectArchiveResultConsumerService extends ArchiveResultConsumerService {

        private FailingConnectArchiveResultConsumerService(ResultPublisherProperties properties) {
            super(
                    properties,
                    entry -> {
                    },
                    new InMemoryResultPublisherCheckpoint(properties)
            );
        }

        @Override
        protected Aeron connectAeron() {
            throw new IllegalStateException("aeron unavailable");
        }
    }

    private static final class RecordingPublisher implements ResultPublisher {

        private final List<String> events;

        private boolean fail;

        private RecordingPublisher(List<String> events) {
            this.events = events;
        }

        @Override
        public void publish(ResultLogEntry entry) {
            events.add("publish:" + entry.resultSerialNum());
            if (fail) {
                throw new IllegalStateException("publish failed");
            }
        }
    }

    private static final class RecordingCheckpoint implements ResultPublisherCheckpoint {

        private final List<String> events;

        private long nextReplayPosition;

        private long lastResultSerialNum;

        private RecordingCheckpoint(List<String> events) {
            this.events = events;
        }

        @Override
        public long nextReplayPosition() {
            return nextReplayPosition;
        }

        @Override
        public long lastResultSerialNum() {
            return lastResultSerialNum;
        }

        @Override
        public void markPublished(ResultLogEntry entry) {
            events.add("checkpoint:" + entry.resultSerialNum());
            nextReplayPosition = entry.endPosition();
            lastResultSerialNum = entry.resultSerialNum();
        }
    }
}
