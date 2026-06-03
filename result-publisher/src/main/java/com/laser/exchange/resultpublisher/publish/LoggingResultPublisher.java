package com.laser.exchange.resultpublisher.publish;

import com.laser.exchange.resultpublisher.archive.ResultLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "laser.result-publisher", name = "publisher-type", havingValue = "logging", matchIfMissing = true)
public class LoggingResultPublisher implements ResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingResultPublisher.class);

    @Override
    public void publish(ResultLogEntry entry) {
        log.info("[ResultPublisher] resultSerialNum={}, requestSerialNum={}, templateId={}, recordingId={}, startPosition={}, endPosition={}, payloadHash={}",
                entry.resultSerialNum(),
                entry.requestSerialNum(),
                entry.templateId(),
                entry.recordingId(),
                entry.startPosition(),
                entry.endPosition(),
                entry.payloadHash());
    }
}
