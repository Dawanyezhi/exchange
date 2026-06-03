package com.laser.exchange.resultpublisher.checkpoint;

import com.laser.exchange.resultpublisher.ResultFrameFixtures;
import com.laser.exchange.resultpublisher.archive.ResultLogEntry;
import com.laser.exchange.resultpublisher.config.ResultPublisherProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcResultPublisherCheckpointTest {

    @Test
    @DisplayName("无持久化记录时按 startPosition 初始化并在发布后插入 checkpoint")
    void initializesFromStartPositionAndInsertsAfterPublishedEntry() {
        ResultPublisherProperties properties = properties();
        properties.setStartPosition(128L);
        FakeResultPublisherCheckpointRepository repository = new FakeResultPublisherCheckpointRepository();
        JdbcResultPublisherCheckpoint checkpoint = new JdbcResultPublisherCheckpoint(properties, repository);

        assertEquals(128L, checkpoint.nextReplayPosition());
        assertEquals(0L, checkpoint.lastResultSerialNum());

        checkpoint.markPublished(resultLogEntry(3L, 256L, 384L));

        assertEquals(384L, checkpoint.nextReplayPosition());
        assertEquals(3L, checkpoint.lastResultSerialNum());
        assertEquals(1, repository.insertCount);
        assertEquals(0, repository.updateCount);
        assertEquals(384L, repository.current.getNextReplayPosition());
        assertEquals(3L, repository.current.getLastResultSerialNum());
    }

    @Test
    @DisplayName("存在持久化记录时从数据库游标恢复并按版本单调推进")
    void loadsPersistedCheckpointAndUpdatesWithVersion() {
        ResultPublisherProperties properties = properties();
        FakeResultPublisherCheckpointRepository repository = new FakeResultPublisherCheckpointRepository();
        repository.current = entity(7L, 512L, 5L, 2L);
        JdbcResultPublisherCheckpoint checkpoint = new JdbcResultPublisherCheckpoint(properties, repository);

        assertEquals(512L, checkpoint.nextReplayPosition());
        assertEquals(5L, checkpoint.lastResultSerialNum());

        checkpoint.markPublished(resultLogEntry(6L, 512L, 640L));

        assertEquals(640L, checkpoint.nextReplayPosition());
        assertEquals(6L, checkpoint.lastResultSerialNum());
        assertEquals(0, repository.insertCount);
        assertEquals(1, repository.updateCount);
        assertEquals(2L, repository.lastExpectedVersion);
        assertEquals(3L, repository.current.getVersion());
    }

    @Test
    @DisplayName("版本冲突时重新加载数据库 checkpoint 并抛出异常")
    void reloadsCheckpointAndFailsOnVersionConflict() {
        ResultPublisherProperties properties = properties();
        FakeResultPublisherCheckpointRepository repository = new FakeResultPublisherCheckpointRepository();
        repository.current = entity(7L, 512L, 5L, 2L);
        repository.updateResult = 0;
        JdbcResultPublisherCheckpoint checkpoint = new JdbcResultPublisherCheckpoint(properties, repository);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> checkpoint.markPublished(resultLogEntry(6L, 512L, 640L))
        );

        assertEquals("failed to update result-publisher checkpoint, version=2", exception.getMessage());
        assertEquals(512L, checkpoint.nextReplayPosition());
        assertEquals(5L, checkpoint.lastResultSerialNum());
    }

    private static ResultPublisherProperties properties() {
        ResultPublisherProperties properties = new ResultPublisherProperties();
        properties.setResultChannel("aeron:ipc");
        properties.setResultStreamId(1001);
        return properties;
    }

    private static ResultPublisherCheckpointEntity entity(long id,
                                                          long nextReplayPosition,
                                                          long lastResultSerialNum,
                                                          long version) {
        ResultPublisherCheckpointEntity entity = new ResultPublisherCheckpointEntity();
        entity.setId(id);
        entity.setResultChannel("aeron:ipc");
        entity.setResultStreamId(1001);
        entity.setRecordingId(1L);
        entity.setNextReplayPosition(nextReplayPosition);
        entity.setLastResultSerialNum(lastResultSerialNum);
        entity.setLastStartPosition(Math.max(0L, nextReplayPosition - 128L));
        entity.setLastEndPosition(nextReplayPosition);
        entity.setLastRequestSerialNum(1_000L + lastResultSerialNum);
        entity.setLastTemplateId(1);
        entity.setLastPayloadHash(30817);
        entity.setVersion(version);
        return entity;
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

    private static final class FakeResultPublisherCheckpointRepository extends ResultPublisherCheckpointRepository {

        private ResultPublisherCheckpointEntity current;

        private int insertCount;

        private int updateCount;

        private int updateResult = 1;

        private long lastExpectedVersion;

        private FakeResultPublisherCheckpointRepository() {
            super(null);
        }

        @Override
        public Optional<ResultPublisherCheckpointEntity> findByResultStream(String resultChannel, int resultStreamId) {
            return Optional.ofNullable(current);
        }

        @Override
        public int insert(ResultPublisherCheckpointEntity checkpoint) {
            insertCount++;
            checkpoint.setId(1L);
            current = copy(checkpoint);
            return 1;
        }

        @Override
        public int updateIfVersionMatchesAndProgresses(ResultPublisherCheckpointEntity checkpoint, long expectedVersion) {
            updateCount++;
            lastExpectedVersion = expectedVersion;
            if (updateResult != 1) {
                return updateResult;
            }
            checkpoint.setId(current.getId());
            checkpoint.setVersion(expectedVersion + 1);
            current = copy(checkpoint);
            return 1;
        }

        private ResultPublisherCheckpointEntity copy(ResultPublisherCheckpointEntity source) {
            ResultPublisherCheckpointEntity target = new ResultPublisherCheckpointEntity();
            target.setId(source.getId());
            target.setResultChannel(source.getResultChannel());
            target.setResultStreamId(source.getResultStreamId());
            target.setRecordingId(source.getRecordingId());
            target.setNextReplayPosition(source.getNextReplayPosition());
            target.setLastResultSerialNum(source.getLastResultSerialNum());
            target.setLastStartPosition(source.getLastStartPosition());
            target.setLastEndPosition(source.getLastEndPosition());
            target.setLastRequestSerialNum(source.getLastRequestSerialNum());
            target.setLastTemplateId(source.getLastTemplateId());
            target.setLastPayloadHash(source.getLastPayloadHash());
            target.setVersion(source.getVersion());
            target.setCreatedAt(source.getCreatedAt());
            target.setUpdatedAt(source.getUpdatedAt());
            return target;
        }
    }
}
