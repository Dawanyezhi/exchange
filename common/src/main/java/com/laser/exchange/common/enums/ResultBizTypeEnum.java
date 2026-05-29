package com.laser.exchange.common.enums;

import lombok.Getter;

/**
 * MatchResult 业务类型。
 *
 * <p>每个 MatchResult 在生成时绑定一个 bizType，下游消费者据此分发到不同处理通道。
 */
@Getter
public enum ResultBizTypeEnum {

    /** 正常的挂单结果 */
    PLACE_ORDER((byte) 1),
    /** 撮合成交结果 */
    MATCH((byte) 2),
    /** 撤单结果 */
    CANCEL((byte) 3),
    /** 上币 */
    SYMBOL_UP((byte) 4),
    /** 下币 */
    SYMBOL_DOWN((byte) 5),
    /** 正常币对停止/开启交易 */
    TRADE_SWITCH((byte) 6);

    private final byte code;

    ResultBizTypeEnum(byte code) {
        this.code = code;
    }

    public static ResultBizTypeEnum of(byte code) {
        for (ResultBizTypeEnum v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown ResultBizType code: " + code);
    }
}
