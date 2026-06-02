package com.laser.exchange.matching.resultLog;

import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.matching.config.AeronClusterConfiguration;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * 基于 Aeron Archive 的撮合结果日志写入器。
 *
 * <p>这是 matching 进程内唯一的生产结果持久化实现。初始化失败直接抛出异常，
 * 让 matching 启动失败，避免降级到不可恢复的内存路径。
 */
@Slf4j
@Component
public class ArchiveResultLogWriter implements ResultLogWriter {

    /** 默认 result stream channel，IPC 走共享内存避免网络栈开销。 */
    public static final String DEFAULT_RESULT_CHANNEL = "aeron:ipc";

    /** result 专用 streamId，与 cluster log/snapshot 的 streamId 区隔。 */
    public static final int DEFAULT_RESULT_STREAM_ID = 1001;

    @Resource
    private AeronClusterConfiguration aeronClusterConfiguration;

    @Value("${laser.matching.result-log.channel:" + DEFAULT_RESULT_CHANNEL + "}")
    private String channel;

    @Value("${laser.matching.result-log.stream-id:" + DEFAULT_RESULT_STREAM_ID + "}")
    private int streamId;

    private final MutableDirectBuffer encodeBuffer = new ExpandableArrayBuffer(1024);

    private AeronArchive aeronArchive;
    private ExclusivePublication publication;
    private long committedResultSerialNum = 0L;
    private volatile boolean initialized = false;

    /**
     * 由 ClusteredService.onStart 在 Cluster/Archive 启动完成后调用。
     */
    public synchronized void init(Aeron aeron) {
        if (initialized) {
            return;
        }

        int nodeId = aeronClusterConfiguration.getNodeId();
        String archiveControlChannel = aeronClusterConfiguration.getChannelPrefix()
                + (AeronClusterConfiguration.getARCHIVE_CONTROL_PORT_BASE() + nodeId);

        try {
            AeronArchive.Context ctx = new AeronArchive.Context()
                    // 复用外层aeron组件
                    .aeron(aeron)
                    // AeronArchive 不拥有这个 Aeron 实例，关闭 AeronArchive 时不会顺手关闭 aeron
                    .ownsAeronClient(false)
                    // 控制通道
                    .controlRequestChannel(archiveControlChannel)
                    .clientName("matching-result-log-writer-" + nodeId);

            aeronArchive = AeronArchive.connect(ctx);
            publication = aeron.addExclusivePublication(channel, streamId);

            long recordingSubscriptionId = aeronArchive.startRecording(channel, streamId, SourceLocation.LOCAL);

            initialized = true;
            log.info("[ArchiveResultLogWriter] initialized channel={}, streamId={}, archiveControl={}, recordingSubscriptionId={}",
                    channel, streamId, archiveControlChannel, recordingSubscriptionId);
        } catch (Exception e) {
            CloseHelper.quietClose(publication);
            CloseHelper.quietClose(aeronArchive);
            throw new IllegalStateException("ArchiveResultLogWriter init failed", e);
        }
    }

    @Override
    public void append(List<MatchResult> results) {
        ensureInitialized();

        if (results == null || results.isEmpty()) {
            return;
        }
        for (MatchResult r : results) {
            if (r.getResultSerialNum() <= committedResultSerialNum) {
                throw new IllegalStateException("resultSerialNum out of order: incoming="
                        + r.getResultSerialNum() + ", committed=" + committedResultSerialNum);
            }

            int length = r.encode(encodeBuffer, 0);
            offerWithBackpressure(encodeBuffer, length, r.getResultSerialNum());
            committedResultSerialNum = r.getResultSerialNum();
        }
    }

    private void offerWithBackpressure(MutableDirectBuffer buffer, int length, long resultSerialNum) {
        long result;
        int spins = 0;

        while ((result = publication.offer(buffer, 0, length)) < 0L) {
            if (result == Publication.NOT_CONNECTED) {
                log.warn("[ArchiveResultLogWriter] not connected yet, resultSerialNum={}, spinning", resultSerialNum);
            } else if (result == Publication.CLOSED) {
                throw new IllegalStateException("result log publication closed, resultSerialNum=" + resultSerialNum);
            } else if (result == Publication.MAX_POSITION_EXCEEDED) {
                throw new IllegalStateException("result log publication max position exceeded, resultSerialNum=" + resultSerialNum);
            }

            if (++spins > 1024) {
                LockSupport.parkNanos(1L);
                spins = 0;
            } else {
                Thread.onSpinWait();
            }
        }
    }

    @Override
    public long getCommittedResultSerialNum() {
        return committedResultSerialNum;
    }

    /**
     * 快照恢复时使用：覆写当前已提交的最大序号。
     */
    public void restoreCommittedResultSerialNum(long committedResultSerialNum) {
        log.info("[ArchiveResultLogWriter] restore committedResultSerialNum from {} to {}",
                this.committedResultSerialNum, committedResultSerialNum);
        this.committedResultSerialNum = committedResultSerialNum;
    }

    private void ensureInitialized() {
        if (!initialized || publication == null) {
            throw new IllegalStateException("ArchiveResultLogWriter is not initialized");
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        CloseHelper.quietClose(publication);
        CloseHelper.quietClose(aeronArchive);
        initialized = false;
        log.info("[ArchiveResultLogWriter] closed channel={}, streamId={}, committedResultSerialNum={}",
                channel, streamId, committedResultSerialNum);
    }
}
