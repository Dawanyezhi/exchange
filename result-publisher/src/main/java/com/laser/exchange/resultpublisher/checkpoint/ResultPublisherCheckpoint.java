package com.laser.exchange.resultpublisher.checkpoint;

import com.laser.exchange.resultpublisher.archive.ResultLogEntry;

public interface ResultPublisherCheckpoint {

    long nextReplayPosition();

    long lastResultSerialNum();

    void markPublishedInMemory(ResultLogEntry entry);
}
