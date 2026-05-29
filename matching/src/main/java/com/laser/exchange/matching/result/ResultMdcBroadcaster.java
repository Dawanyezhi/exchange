package com.laser.exchange.matching.result;

import com.laser.exchange.common.result.MatchResult;
import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import lombok.extern.slf4j.Slf4j;
import org.agrona.CloseHelper;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;

import java.util.List;

/**
 * MatchResult MDC (Multi-Destination-Control) 广播器。
 *
 * <p><b>语义</b>：一个 publication，多个动态订阅者（mock-counter / 清算 / 风控 / UI 推送）都能并行接收。
 *
 * <p><b>channel 约定</b>：
 * <ul>
 *   <li>Publisher: {@code aeron:udp?control=host:port|control-mode=dynamic}</li>
 *   <li>Subscriber: {@code aeron:udp?endpoint=sub-host:sub-port|control=pub-host:pub-port}</li>
 * </ul>
 *
 * <p><b>与 archive 的关系</b>：
 * archive 负责持久化（重启可 replay）；MDC 负责实时广播（下游 push）。
 * 同一 MatchResult 会同时写 archive + 广播到 MDC。
 *
 * <p><b>线程模型</b>：cluster 状态机单线程调用 {@link #broadcastBatch(List)}，
 * 底层 Aeron Publication.offer 是非阻塞；背压时 fire-and-forget，不拖累主流程。
 */
@Slf4j
public class ResultMdcBroadcaster {

    private final Aeron aeron;
    private final String channel;
    private final int streamId;
    private final ExclusivePublication publication;
    private final MutableDirectBuffer encodeBuffer = new ExpandableArrayBuffer(1024);

    private long broadcastCount = 0L;
    private long droppedCount = 0L;

    public ResultMdcBroadcaster(Aeron aeron, String channel, int streamId) {
        this.aeron = aeron;
        this.channel = channel;
        this.streamId = streamId;
        this.publication = aeron.addExclusivePublication(channel, streamId);
        log.info("[ResultMdcBroadcaster] publisher initialized channel={}, streamId={}, sessionId={}",
                channel, streamId, publication.sessionId());
    }

    /**
     * 批量广播一个 request 周期产生的 MatchResult。
     * 订阅方（mock-counter）会在 egress listener 中按 SBE 解码。
     */
    public void broadcastBatch(List<MatchResult> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (MatchResult r : batch) {
            int length = r.encode(encodeBuffer, 0);
            long result = publication.offer(encodeBuffer, 0, length);
            if (result < 0) {
                // MDC 背压 fire-and-forget：下游丢一条不应阻塞撮合主流程
                droppedCount++;
                if (droppedCount % 1000 == 1) {
                    log.warn("[ResultMdcBroadcaster] offer back-pressured, dropped {}th result, serialNum={}, code={}",
                            droppedCount, r.getResultSerialNum(), result);
                }
            } else {
                broadcastCount++;
            }
        }
    }

    public long getBroadcastCount() {
        return broadcastCount;
    }

    public long getDroppedCount() {
        return droppedCount;
    }

    public void close() {
        CloseHelper.quietClose(publication);
        log.info("[ResultMdcBroadcaster] closed. broadcast={}, dropped={}", broadcastCount, droppedCount);
    }
}
