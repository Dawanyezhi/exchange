package com.laser.exchange.resultpublisher.checkpoint;

import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

@Repository
public class ResultPublisherCheckpointRepository {

    private final ResultPublisherCheckpointMapper mapper;

    public ResultPublisherCheckpointRepository(ResultPublisherCheckpointMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<ResultPublisherCheckpointEntity> findByResultStream(String resultChannel, int resultStreamId) {
        Objects.requireNonNull(resultChannel, "resultChannel");
        return Optional.ofNullable(mapper.selectByResultStream(resultChannel, resultStreamId));
    }

    public int insert(ResultPublisherCheckpointEntity checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        return mapper.insertCheckpoint(checkpoint);
    }

    public int updateIfVersionMatchesAndProgresses(ResultPublisherCheckpointEntity checkpoint, long expectedVersion) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        return mapper.updateCheckpointIfVersionMatchesAndProgresses(checkpoint, expectedVersion);
    }
}
