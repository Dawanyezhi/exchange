package com.laser.exchange.matching.resultRepoModule;

import com.laser.exchange.common.result.MatchResult;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import lombok.extern.slf4j.Slf4j;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;

import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Aeron Archive 持久化实现。
 *
 * <p><b>架构</b>：
 * <ul>
 *   <li>复用 ClusteredMediaDriver 启动的 Archive，不另起进程</li>
 *   <li>新建一个独立 IPC channel + 专用 streamId，避免与 raftlog/snapshot 串流</li>
 *   <li>调用 AeronArchive.startRecording(...) 让 Archive 记录该 stream</li>
 *   <li>每条 MatchResult 编码为 SBE 后通过 ExclusivePublication.offer 写入</li>
 * </ul>
 *
 * <p><b>顺序保证</b>：
 * 撮合状态机单线程调用 {@link #persist(List)}，且 results 已按 resultSerialNum 升序排好；
 * Aeron Publication 单生产者语义自然保留写入顺序。
 *
 * <p><b>背压</b>：
 * offer() 返回 BACK_PRESSURED / NOT_CONNECTED 时 busy-spin (parkNanos 1) 重试。
 * 撮合状态机内严禁 sleep/wait，所以采用最低代价的 yield。
 */
@Slf4j
public class ArchiveResultRepository implements ResultRepository {

    /** 默认的 result stream channel；IPC 走共享内存避免网络栈开销 */
    public static final String DEFAULT_RESULT_CHANNEL = "aeron:ipc";

    /** result 专用 streamId，与 cluster log/snapshot 的 streamId 区隔 */
    public static final int DEFAULT_RESULT_STREAM_ID = 1001;

    private final Aeron aeron;
    private final AeronArchive aeronArchive;
    private final String channel;
    private final int streamId;

    private final ExclusivePublication publication;
    private final MutableDirectBuffer encodeBuffer = new ExpandableArrayBuffer(1024);

    private long maxResultSerialNum = 0L;

    public ArchiveResultRepository(Aeron aeron, AeronArchive aeronArchive,
                                   String channel, int streamId) {
        this.aeron = aeron;
        this.aeronArchive = aeronArchive;
        this.channel = channel;
        this.streamId = streamId;

        this.publication = aeron.addExclusivePublication(channel, streamId);
        // 触发 Archive 开始记录此 publication
        long recordingSubscriptionId = aeronArchive.startRecording(channel, streamId, SourceLocation.LOCAL);
        log.info("[ArchiveResultRepository] started recording channel={}, streamId={}, recordingSubscriptionId={}",
                channel, streamId, recordingSubscriptionId);
    }

    @Override
    public void persist(List<MatchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        for (MatchResult r : results) {
            if (r.getResultSerialNum() <= maxResultSerialNum) {
                log.warn("[ArchiveResultRepository] resultSerialNum={} is already persisted, skipping", r.getResultSerialNum());
                continue;
            }
            int length = r.encode(encodeBuffer, 0);

            // 带有重试的写入archive
            offerWithBackpressure(encodeBuffer, length, r.getResultSerialNum());
            maxResultSerialNum = r.getResultSerialNum();
        }
    }

    private void offerWithBackpressure(MutableDirectBuffer buffer, int length, long resultSerialNum) {
        long result;
        int spins = 0;
        while ((result = publication.offer(buffer, 0, length)) < 0L) {
            if (result == Publication.NOT_CONNECTED) {
                log.warn("[ArchiveResultRepository] not connected yet, resultSerialNum={}, spinning", resultSerialNum);
            } else if (result == Publication.CLOSED) {
                // todo 待处理状态机问题
                log.error("[ArchiveResultRepository] publication closed, resultSerialNum={}, exiting", resultSerialNum);
            } else if (result == Publication.MAX_POSITION_EXCEEDED) {
                log.error("[ArchiveResultRepository] publication max position exceeded, resultSerialNum={}, exiting", resultSerialNum);
            }

            // BACK_PRESSURED or ADMIN_ACTION: 让出 CPU 重试
            if (++spins > 1024) {
                LockSupport.parkNanos(1L);
                spins = 0;
            } else {
                Thread.onSpinWait();
            }
        }
    }

    @Override
    public long getMaxResultSerialNum() {
        return maxResultSerialNum;
    }

    /**
     * 快照恢复时使用：覆写当前已持久化的最大序号 (从 archive 回放或快照读入)。
     */
    public void restoreMaxResultSerialNum(long max) {
        log.info("[ArchiveResultRepository] restore maxResultSerialNum from {} to {}", maxResultSerialNum, max);
        this.maxResultSerialNum = max;
    }

    @Override
    public void close() {
        CloseHelper.quietClose(publication);
        log.info("[ArchiveResultRepository] closed publication channel={}, streamId={}", channel, streamId);
    }
}
