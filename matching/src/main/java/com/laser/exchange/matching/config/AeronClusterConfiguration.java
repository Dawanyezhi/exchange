package com.laser.exchange.matching.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "laser.aeron.cluster.config")
@Getter
@Setter
public class AeronClusterConfiguration {

    @Getter
    private static final String CLUSTER_DIR_PREFIX = "node-";
    @Getter
    private static final String AERON_DIR = "aeron";
    @Getter
    private static final int ARCHIVE_CONTROL_PORT_BASE = 8010;
    @Getter
    private static final int ARCHIVE_REPLICATION_PORT_BASE = 8020;
    @Getter
    private static final int ARCHIVE_RECORDING_EVENTS_PORT_BASE = 8030;

    // 共识模块 base
    @Getter
    private static final int CLIENT_FACING_PORT_BASE = 9000;
    @Getter
    private static final int MEMBER_FACING_PORT_BASE = 9100;
    @Getter
    private static final int LOG_PORT_BASE = 9200;
    // 快照传输
    @Getter
    private static final int TRANSFER_PORT_BASE = 9300;
    // 共识模块复制日志channel base
    @Getter
    private static final int CONSENSUS_REPLICATION_PORT_BASE = 8040;


    private Integer nodeId;

    private String baseDir;

    private String channelPrefix;

    private Integer clusterNodeSize = 3;

    private String nodeIp;

}
