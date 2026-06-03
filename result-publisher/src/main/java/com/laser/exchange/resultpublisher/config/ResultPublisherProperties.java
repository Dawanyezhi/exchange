package com.laser.exchange.resultpublisher.config;

import com.laser.exchange.resultpublisher.archive.ArchiveResultLogReaderConfig;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "laser.result-publisher")
public class ResultPublisherProperties {

    private boolean enabled = true;

    private int nodeId = 0;

    private String baseDir = "/Users/dawanyezhi/temp/aeron";

    private String channelPrefix = "aeron:udp?endpoint=localhost:";

    private int archiveControlPortBase = 8010;

    private String resultChannel = ArchiveResultLogReaderConfig.DEFAULT_RESULT_CHANNEL;

    private int resultStreamId = ArchiveResultLogReaderConfig.DEFAULT_RESULT_STREAM_ID;

    private String replayChannel = ArchiveResultLogReaderConfig.DEFAULT_REPLAY_CHANNEL;

    private int replayStreamId = ArchiveResultLogReaderConfig.DEFAULT_REPLAY_STREAM_ID;

    private int fragmentLimit = ArchiveResultLogReaderConfig.DEFAULT_FRAGMENT_LIMIT;

    private int idleSpinLimit = ArchiveResultLogReaderConfig.DEFAULT_IDLE_SPIN_LIMIT;

    private long idleMaxSpins = ArchiveResultLogReaderConfig.DEFAULT_IDLE_MAX_SPINS;

    private long idleMaxYields = ArchiveResultLogReaderConfig.DEFAULT_IDLE_MAX_YIELDS;

    private long idleMinParkNs = ArchiveResultLogReaderConfig.DEFAULT_IDLE_MIN_PARK_NS;

    private long idleMaxParkNs = ArchiveResultLogReaderConfig.DEFAULT_IDLE_MAX_PARK_NS;

    private long startPosition = 0L;

    private long retryIntervalMs = 1_000L;

    private long retryMaxIntervalMs = 30_000L;

    private long initialConnectTimeoutMs = 30_000L;

    private long archiveMessageTimeoutMs = 3_000L;

    private long aeronDriverTimeoutMs = 3_000L;

    private String publisherType = "logging";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getNodeId() {
        return nodeId;
    }

    public void setNodeId(int nodeId) {
        this.nodeId = nodeId;
    }

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public String getChannelPrefix() {
        return channelPrefix;
    }

    public void setChannelPrefix(String channelPrefix) {
        this.channelPrefix = channelPrefix;
    }

    public int getArchiveControlPortBase() {
        return archiveControlPortBase;
    }

    public void setArchiveControlPortBase(int archiveControlPortBase) {
        this.archiveControlPortBase = archiveControlPortBase;
    }

    public String getResultChannel() {
        return resultChannel;
    }

    public void setResultChannel(String resultChannel) {
        this.resultChannel = resultChannel;
    }

    public int getResultStreamId() {
        return resultStreamId;
    }

    public void setResultStreamId(int resultStreamId) {
        this.resultStreamId = resultStreamId;
    }

    public String getReplayChannel() {
        return replayChannel;
    }

    public void setReplayChannel(String replayChannel) {
        this.replayChannel = replayChannel;
    }

    public int getReplayStreamId() {
        return replayStreamId;
    }

    public void setReplayStreamId(int replayStreamId) {
        this.replayStreamId = replayStreamId;
    }

    public int getFragmentLimit() {
        return fragmentLimit;
    }

    public void setFragmentLimit(int fragmentLimit) {
        this.fragmentLimit = fragmentLimit;
    }

    public int getIdleSpinLimit() {
        return idleSpinLimit;
    }

    public void setIdleSpinLimit(int idleSpinLimit) {
        this.idleSpinLimit = idleSpinLimit;
    }

    public long getIdleMaxSpins() {
        return idleMaxSpins;
    }

    public void setIdleMaxSpins(long idleMaxSpins) {
        this.idleMaxSpins = idleMaxSpins;
    }

    public long getIdleMaxYields() {
        return idleMaxYields;
    }

    public void setIdleMaxYields(long idleMaxYields) {
        this.idleMaxYields = idleMaxYields;
    }

    public long getIdleMinParkNs() {
        return idleMinParkNs;
    }

    public void setIdleMinParkNs(long idleMinParkNs) {
        this.idleMinParkNs = idleMinParkNs;
    }

    public long getIdleMaxParkNs() {
        return idleMaxParkNs;
    }

    public void setIdleMaxParkNs(long idleMaxParkNs) {
        this.idleMaxParkNs = idleMaxParkNs;
    }

    public long getStartPosition() {
        return startPosition;
    }

    public void setStartPosition(long startPosition) {
        this.startPosition = startPosition;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }

    public long getRetryMaxIntervalMs() {
        return retryMaxIntervalMs;
    }

    public void setRetryMaxIntervalMs(long retryMaxIntervalMs) {
        this.retryMaxIntervalMs = retryMaxIntervalMs;
    }

    public long getInitialConnectTimeoutMs() {
        return initialConnectTimeoutMs;
    }

    public void setInitialConnectTimeoutMs(long initialConnectTimeoutMs) {
        this.initialConnectTimeoutMs = initialConnectTimeoutMs;
    }

    public long getArchiveMessageTimeoutMs() {
        return archiveMessageTimeoutMs;
    }

    public void setArchiveMessageTimeoutMs(long archiveMessageTimeoutMs) {
        this.archiveMessageTimeoutMs = archiveMessageTimeoutMs;
    }

    public long getAeronDriverTimeoutMs() {
        return aeronDriverTimeoutMs;
    }

    public void setAeronDriverTimeoutMs(long aeronDriverTimeoutMs) {
        this.aeronDriverTimeoutMs = aeronDriverTimeoutMs;
    }

    public String getPublisherType() {
        return publisherType;
    }

    public void setPublisherType(String publisherType) {
        this.publisherType = publisherType;
    }

    public String aeronDirectoryName() {
        return baseDir + "/node-" + nodeId + "/aeron";
    }

    public String archiveControlChannel() {
        return channelPrefix + (archiveControlPortBase + nodeId);
    }

    public ArchiveResultLogReaderConfig toReaderConfig() {
        return new ArchiveResultLogReaderConfig(
                resultChannel,
                resultStreamId,
                replayChannel,
                replayStreamId,
                fragmentLimit,
                idleSpinLimit,
                () -> new BackoffIdleStrategy(
                        idleMaxSpins,
                        idleMaxYields,
                        idleMinParkNs,
                        idleMaxParkNs
                )
        );
    }
}
