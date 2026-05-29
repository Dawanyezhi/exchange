package com.laser.exchange.matching.resultRepoModule;

import com.laser.exchange.common.result.MatchResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内存版 {@link ResultRepository}，用于单元测试。
 *
 * <p>不要在生产环境使用——重启即丢。
 *
 * <p>线程安全：撮合状态机本身单线程访问，但为方便多线程测试断言，仍使用 synchronized 守护内部 list。
 */
@Slf4j
public class InMemoryResultRepository implements ResultRepository {

    private final List<MatchResult> store = new ArrayList<>(1024);
    private long maxResultSerialNum = 0L;

    @Override
    public synchronized void persist(List<MatchResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        for (MatchResult r : results) {
            if (r.getResultSerialNum() <= maxResultSerialNum) {
                throw new IllegalStateException(
                        "resultSerialNum out of order: incoming=" + r.getResultSerialNum()
                                + ", maxSeen=" + maxResultSerialNum);
            }
            store.add(r);
            maxResultSerialNum = r.getResultSerialNum();
        }
        log.debug("[InMemoryResultRepository] persisted {} results, maxSerialNum={}",
                results.size(), maxResultSerialNum);
    }

    @Override
    public synchronized long getMaxResultSerialNum() {
        return maxResultSerialNum;
    }

    @Override
    public void close() {
        // no-op
    }

    /** 仅供测试断言 */
    public synchronized List<MatchResult> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(store));
    }
}
