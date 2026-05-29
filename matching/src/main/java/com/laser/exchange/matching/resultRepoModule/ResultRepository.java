package com.laser.exchange.matching.resultRepoModule;

import com.laser.exchange.common.result.MatchResult;

import java.util.List;

/**
 * 撮合结果持久化抽象。
 *
 * <p>核心契约：
 * <ul>
 *   <li>{@link #persist(List)} 必须保证 result 入队顺序 = resultSerialNum 升序</li>
 *   <li>持久化是异步刷盘 (aeron archive 默认行为)；返回不代表已落盘</li>
 *   <li>{@link #getMaxResultSerialNum()} 返回当前已发布的最大 resultSerialNum，用于快照恢复时 reconcile</li>
 * </ul>
 */
public interface ResultRepository {

    /**
     * 持久化一个 request 周期产生的所有 result。
     *
     * @param results 已按 resultSerialNum 升序的不可变快照
     */
    void persist(List<MatchResult> results);

    /**
     * 当前已持久化的最大 resultSerialNum。0 表示尚无持久化记录。
     */
    long getMaxResultSerialNum();

    /**
     * 关闭持久化资源 (channel/publication/aeron client)。
     */
    void close();
}
