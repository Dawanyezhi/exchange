package com.laser.exchange.matching.validation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Request 序号连续性校验器。
 *
 * <p>语义：客户端按全局严格 +1 序列发送请求 (1, 2, 3, ...)，
 * 服务端在每个请求入口校验 newSerialNum == lastSerialNum + 1。
 *
 * <p>状态机内单线程访问，故 lastSerialNum 用普通 long 字段即可。
 * 严禁使用 AtomicLong——撮合 cluster 状态机的确定性原则要求消除任何隐式内存屏障/竞争点。
 *
 * <p>开关：{@code laser.matching.validation.serial-check-enabled}
 * <ul>
 *   <li>true (默认/生产)：严格校验，跳号丢弃 + 生成 SystemErrorCode=1 的 ErrorMatchResult</li>
 *   <li>false (测试)：跳过校验但仍记录 lastSerialNum</li>
 * </ul>
 *
 * <p>快照恢复：调用 {@link #restoreLastSerialNum(long)} 直接覆写。
 */
@Slf4j
@Component
public class SerialNumValidator {

    private final boolean enabled;

    /** 单线程状态机访问，不要换 AtomicLong */
    private long lastSerialNum = 0L;

    public SerialNumValidator(
            @Value("${laser.matching.validation.serial-check-enabled:true}") boolean enabled) {
        this.enabled = enabled;
        log.info("[SerialNumValidator] init enabled={}", enabled);
    }

    /**
     * 校验并推进 lastSerialNum。
     *
     * @return true 合法 (开关关时永远 true)；false 不连续，调用方应丢弃请求并生成错误结果
     */
    public boolean validateAndAdvance(long newSerialNum) {
        if (!enabled) {
            lastSerialNum = newSerialNum;
            return true;
        }
        long expected = lastSerialNum + 1;
        if (newSerialNum != expected) {
            log.error("[SerialNumValidator] serialNum NOT continuous: expected={}, actual={}, lastSerialNum={}",
                    expected, newSerialNum, lastSerialNum);
            return false;
        }
        lastSerialNum = newSerialNum;
        return true;
    }

    public long getLastSerialNum() {
        return lastSerialNum;
    }

    /**
     * 快照恢复入口：覆写 lastSerialNum。
     */
    public void restoreLastSerialNum(long serialNum) {
        log.info("[SerialNumValidator] restore lastSerialNum from {} to {}", lastSerialNum, serialNum);
        lastSerialNum = serialNum;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
