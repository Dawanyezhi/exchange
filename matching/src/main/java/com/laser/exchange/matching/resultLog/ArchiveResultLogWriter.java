package com.laser.exchange.matching.resultLog;

import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.matching.config.AeronClusterConfiguration;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.status.CountersReader;
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

    @Value("${laser.matching.result-log.commit-timeout-ms:3000}")
    private long commitTimeoutMs;

    private final MutableDirectBuffer encodeBuffer = new ExpandableArrayBuffer(1024);

    private AeronArchive aeronArchive;

    private ExclusivePublication publication;

    private CountersReader countersReader;

    private long recordingId = RecordingPos.NULL_RECORDING_ID;

    private long lastOfferedResultSerialNum = 0L;

    private long lastOfferedPosition = 0L;

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
            countersReader = aeron.countersReader();

            long recordingSubscriptionId = aeronArchive.startRecording(channel, streamId, SourceLocation.LOCAL);
            recordingId = awaitRecordingId(publication.sessionId(), recordingSubscriptionId);

            initialized = true;
            log.info("[ArchiveResultLogWriter] initialized channel={}, streamId={}, archiveControl={}, sessionId={}, recordingId={}, recordingSubscriptionId={}",
                    channel, streamId, archiveControlChannel, publication.sessionId(), recordingId, recordingSubscriptionId);
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
            if (r.getResultSerialNum() <= lastOfferedResultSerialNum) {
                throw new IllegalStateException("resultSerialNum out of order: incoming="
                        + r.getResultSerialNum() + ", lastOffered=" + lastOfferedResultSerialNum);
            }

            int length = r.encode(encodeBuffer, 0);

            // 背压重试方式offer
            long offeredPosition = offerWithBackpressure(encodeBuffer, length, r.getResultSerialNum());
            lastOfferedPosition = offeredPosition;
            lastOfferedResultSerialNum = r.getResultSerialNum();
        }
    }

    private long offerWithBackpressure(MutableDirectBuffer buffer, int length, long resultSerialNum) {
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
        return result;
    }

    private long awaitRecordingId(int sessionId, long recordingSubscriptionId) {
        long deadlineNs = System.nanoTime() + commitTimeoutMs * 1_000_000L;
        int counterId;
        while ((counterId = RecordingPos.findCounterIdBySession(countersReader, sessionId)) == CountersReader.NULL_COUNTER_ID) {
            if (System.nanoTime() >= deadlineNs) {
                throw new IllegalStateException("archive recording counter not found, sessionId="
                        + sessionId + ", recordingSubscriptionId=" + recordingSubscriptionId);
            }
            Thread.onSpinWait();
        }

        long id = RecordingPos.getRecordingId(countersReader, counterId);
        if (id == RecordingPos.NULL_RECORDING_ID) {
            throw new IllegalStateException("archive recordingId is null, sessionId="
                    + sessionId + ", counterId=" + counterId);
        }
        return id;
    }

    private void awaitRecordingPosition(long offeredPosition, long resultSerialNum) {
        long deadlineNs = System.nanoTime() + commitTimeoutMs * 1_000_000L;
        long recordingPosition;
        while ((recordingPosition = aeronArchive.getRecordingPosition(recordingId)) < offeredPosition) {
            if (recordingPosition == AeronArchive.NULL_POSITION) {
                throw new IllegalStateException("archive recording is not active, recordingId="
                        + recordingId + ", resultSerialNum=" + resultSerialNum);
            }
            if (System.nanoTime() >= deadlineNs) {
                throw new IllegalStateException("archive recording position did not catch up, recordingId="
                        + recordingId + ", resultSerialNum=" + resultSerialNum
                        + ", offeredPosition=" + offeredPosition
                        + ", recordingPosition=" + recordingPosition
                        + ", timeoutMs=" + commitTimeoutMs);
            }
            Thread.onSpinWait();
        }
    }

    @Override
    public long getLastOfferedResultSerialNum() {
        return lastOfferedResultSerialNum;
    }

    public long getLastOfferedPosition() {
        return lastOfferedPosition;
    }

    public long getRecordingId() {
        return recordingId;
    }

    /**
     * 快照前使用：等待当前已 offer 的最大 result 被 Archive recording 覆盖。
     */
    @Override
    public void awaitLastOfferedRecorded() {
        ensureInitialized();
        if (lastOfferedPosition <= 0L) {
            return;
        }
        awaitRecordingPosition(lastOfferedPosition, lastOfferedResultSerialNum);
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
        recordingId = RecordingPos.NULL_RECORDING_ID;
        log.info("[ArchiveResultLogWriter] closed channel={}, streamId={}, lastOfferedResultSerialNum={}, lastOfferedPosition={}",
                channel, streamId, lastOfferedResultSerialNum, lastOfferedPosition);
    }
}
