package com.laser.exchange.resultpublisher.checkpoint;

import com.laser.exchange.resultpublisher.archive.ResultLogEntry;
import com.laser.exchange.resultpublisher.config.ResultPublisherProperties;
import com.laser.exchange.resultpublisher.ResultFrameFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryResultPublisherCheckpointTest {

    @Test
    @DisplayName("发布成功后推进下一次 replay position 和 resultSerialNum")
    void advancesAfterPublishedEntry() {
        ResultPublisherProperties properties = new ResultPublisherProperties();
        properties.setStartPosition(128L);
        InMemoryResultPublisherCheckpoint checkpoint = new InMemoryResultPublisherCheckpoint(properties);

        assertEquals(128L, checkpoint.nextReplayPosition());
        assertEquals(0L, checkpoint.lastResultSerialNum());

        checkpoint.markPublished(new ResultLogEntry(
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
        ));

        assertEquals(384L, checkpoint.nextReplayPosition());
        assertEquals(3L, checkpoint.lastResultSerialNum());
    }
}
