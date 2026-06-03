package com.laser.exchange.resultpublisher.service;

import com.laser.exchange.resultpublisher.checkpoint.InMemoryResultPublisherCheckpoint;
import com.laser.exchange.resultpublisher.config.ResultPublisherProperties;
import io.aeron.Aeron;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
