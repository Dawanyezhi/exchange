package com.laser.exchange.resultpublisher.checkpoint;

import com.laser.exchange.resultpublisher.ResultFrameFixtures;
import com.laser.exchange.resultpublisher.archive.ResultLogEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultPublisherCheckpointEntityTest {

    @Test
    @DisplayName("从撮合结果事件创建持久化 checkpoint 实体")
    void createsEntityFromResultLogEntry() {
        ResultLogEntry entry = new ResultLogEntry(
                3L,
                7L,
                256L,
                384L,
                1,
                1003L,
                1_700_000_000_003L,
                new byte[]{1, 2, 3},
                30817,
                ResultFrameFixtures.placeResult(3L, 1003L)
        );

        ResultPublisherCheckpointEntity entity =
                ResultPublisherCheckpointEntity.fromEntry("aeron:ipc", 1001, entry);

        assertEquals("aeron:ipc", entity.getResultChannel());
        assertEquals(1001, entity.getResultStreamId());
        assertEquals(7L, entity.getRecordingId());
        assertEquals(384L, entity.getNextReplayPosition());
        assertEquals(3L, entity.getLastResultSerialNum());
        assertEquals(256L, entity.getLastStartPosition());
        assertEquals(384L, entity.getLastEndPosition());
        assertEquals(1003L, entity.getLastRequestSerialNum());
        assertEquals(1, entity.getLastTemplateId());
        assertEquals(30817, entity.getLastPayloadHash());
        assertEquals(0L, entity.getVersion());
    }
}
