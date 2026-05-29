package com.laser.exchange.counter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端 Request serialNum 生成器。
 *
 * <p>客户端可能多线程发送，故用 AtomicLong——这与服务端单线程 long 的选择不矛盾，
 * 客户端是网关层，并发是常态；服务端是状态机层，确定性是常态。
 *
 * <p>序列从 1 开始，全局单调递增 (newSerialNum = oldSerialNum + 1)，
 * 与服务端 SerialNumValidator 的预期一致。
 */
@Slf4j
@Component
public class RequestSerialNumGenerator {

    private final AtomicLong counter = new AtomicLong(0);

    public long next() {
        return counter.incrementAndGet();
    }

    public long current() {
        return counter.get();
    }

    /** 仅供测试或重连时重置 */
    public void reset() {
        long old = counter.getAndSet(0);
        log.warn("[RequestSerialNumGenerator] reset from {} to 0", old);
    }
}