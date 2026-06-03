package com.laser.exchange.resultpublisher.config;

import com.laser.exchange.resultpublisher.archive.ArchiveResultLogReaderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultPublisherPropertiesTest {

    @Test
    @DisplayName("根据 nodeId 推导 Aeron 目录与 Archive 控制通道")
    void derivesAeronDirectoryAndArchiveControlChannel() {
        ResultPublisherProperties properties = new ResultPublisherProperties();
        properties.setNodeId(2);
        properties.setBaseDir("/tmp/aeron");
        properties.setChannelPrefix("aeron:udp?endpoint=127.0.0.1:");
        properties.setArchiveControlPortBase(8010);

        assertEquals("/tmp/aeron/node-2/aeron", properties.aeronDirectoryName());
        assertEquals("aeron:udp?endpoint=127.0.0.1:8012", properties.archiveControlChannel());
        assertEquals(3_000L, properties.getAeronDriverTimeoutMs());
        assertEquals(30_000L, properties.getRetryMaxIntervalMs());
        assertEquals(30_000L, properties.getInitialConnectTimeoutMs());
        assertEquals(ArchiveResultLogReaderConfig.DEFAULT_IDLE_MAX_SPINS, properties.getIdleMaxSpins());
        assertEquals(ArchiveResultLogReaderConfig.DEFAULT_IDLE_MAX_YIELDS, properties.getIdleMaxYields());
        assertEquals(ArchiveResultLogReaderConfig.DEFAULT_IDLE_MIN_PARK_NS, properties.getIdleMinParkNs());
        assertEquals(ArchiveResultLogReaderConfig.DEFAULT_IDLE_MAX_PARK_NS, properties.getIdleMaxParkNs());
    }
}
