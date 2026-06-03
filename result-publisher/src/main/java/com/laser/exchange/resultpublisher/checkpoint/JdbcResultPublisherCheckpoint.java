package com.laser.exchange.resultpublisher.checkpoint;

import com.laser.exchange.resultpublisher.archive.ResultLogEntry;
import com.laser.exchange.resultpublisher.config.ResultPublisherProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
public class JdbcResultPublisherCheckpoint implements ResultPublisherCheckpoint {

    private static final long INITIAL_RECORDING_ID = -1L;

    private final ResultPublisherProperties properties;

    private final ResultPublisherCheckpointRepository repository;

    private ResultPublisherCheckpointEntity current;

    public JdbcResultPublisherCheckpoint(ResultPublisherProperties properties,
                                         ResultPublisherCheckpointRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    @Override
    public synchronized long recordingId() {
        ensureLoaded();
        return current.getRecordingId();
    }

    @Override
    public synchronized long nextReplayPosition() {
        ensureLoaded();
        return current.getNextReplayPosition();
    }

    @Override
    public synchronized long lastResultSerialNum() {
        ensureLoaded();
        return current.getLastResultSerialNum();
    }

    @Override
    public synchronized void markPublished(ResultLogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        ensureLoaded();

        ResultPublisherCheckpointEntity next = ResultPublisherCheckpointEntity.fromEntry(
                properties.getResultChannel(),
                properties.getResultStreamId(),
                entry
        );
        if (current.getId() == null || !Objects.equals(current.getRecordingId(), next.getRecordingId())) {
            insert(next);
            return;
        }

        long expectedVersion = current.getVersion();
        int updated = repository.updateIfVersionMatchesAndProgresses(next, expectedVersion);
        if (updated != 1) {
            current = repository.findLatestByResultStream(properties.getResultChannel(), properties.getResultStreamId())
                    .orElse(current);
            log.error("failed to update result-publisher checkpoint, error expectedVersion:{}", expectedVersion);
        }

        next.setId(current.getId());
        next.setVersion(expectedVersion + 1);
        current = next;
    }

    private void insert(ResultPublisherCheckpointEntity next) {
        int inserted = repository.insert(next);
        if (inserted != 1) {
            throw new IllegalStateException("failed to insert result-publisher checkpoint");
        }
        current = next;
    }

    private void ensureLoaded() {
        if (current == null) {
            current = loadOrInitialCheckpoint();
        }
    }

    private ResultPublisherCheckpointEntity loadOrInitialCheckpoint() {
        Optional<ResultPublisherCheckpointEntity> checkpoint = repository.findLatestByResultStream(
                properties.getResultChannel(),
                properties.getResultStreamId()
        );
        if (checkpoint.isPresent()) {
            return checkpoint.get();
        }

        ResultPublisherCheckpointEntity initial = new ResultPublisherCheckpointEntity();
        initial.setResultChannel(properties.getResultChannel());
        initial.setResultStreamId(properties.getResultStreamId());
        initial.setRecordingId(INITIAL_RECORDING_ID);
        initial.setNextReplayPosition(properties.getStartPosition());
        initial.setLastResultSerialNum(0L);
        initial.setLastStartPosition(properties.getStartPosition());
        initial.setLastEndPosition(properties.getStartPosition());
        initial.setLastRequestSerialNum(0L);
        initial.setLastTemplateId(0);
        initial.setLastPayloadHash(0);
        initial.setVersion(0L);
        return initial;
    }
}
