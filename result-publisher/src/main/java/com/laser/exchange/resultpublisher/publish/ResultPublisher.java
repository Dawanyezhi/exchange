package com.laser.exchange.resultpublisher.publish;

import com.laser.exchange.resultpublisher.archive.ResultLogEntry;

public interface ResultPublisher {

    void publish(ResultLogEntry entry);
}
