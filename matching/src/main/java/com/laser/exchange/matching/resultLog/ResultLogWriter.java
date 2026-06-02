package com.laser.exchange.matching.resultLog;

import com.laser.exchange.common.result.MatchResult;

import java.util.List;

/**
 * 撮合结果日志写入器。
 *
 * <p>matching 只负责把 {@link MatchResult} 追加到可靠结果日志，不提供 replay 读取能力。
 * 下游实时推送和 replay 查询由后续独立 result-publisher 服务从 Archive 读取。
 */
public interface ResultLogWriter {

    /**
     * 追加一个 request 周期产生的所有 result。
     *
     * @param results 已按 resultSerialNum 升序排列的结果批次
     */
    void append(List<MatchResult> results);

    /**
     * 当前 writer 已 offer 到 Archive publication 的最大 resultSerialNum。0 表示尚无结果。
     */
    long getLastOfferedResultSerialNum();

    /**
     * 等待当前已 offer 的最大 result 被 Archive recording 覆盖。
     */
    void awaitLastOfferedRecorded();

    /**
     * 关闭结果日志写入资源。
     */
    void close();
}
