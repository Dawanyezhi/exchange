package com.laser.exchange.resultpublisher.checkpoint;

import com.laser.exchange.resultpublisher.archive.ResultLogEntry;
import com.laser.exchange.resultpublisher.config.ResultPublisherProperties;
import org.springframework.stereotype.Component;

@Component
public class InMemoryResultPublisherCheckpoint implements ResultPublisherCheckpoint {

    private long nextReplayPosition;

    private long lastResultSerialNum;

    public InMemoryResultPublisherCheckpoint(ResultPublisherProperties properties) {
        this.nextReplayPosition = properties.getStartPosition();
    }

    @Override
    public synchronized long nextReplayPosition() {
        return nextReplayPosition;
    }

    @Override
    public synchronized long lastResultSerialNum() {
        return lastResultSerialNum;
    }

    @Override
    public synchronized void markPublished(ResultLogEntry entry) {
        nextReplayPosition = entry.endPosition();
        lastResultSerialNum = entry.resultSerialNum();
    }
}
