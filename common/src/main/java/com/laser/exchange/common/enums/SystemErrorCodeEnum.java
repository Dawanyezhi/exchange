package com.laser.exchange.common.enums;

import lombok.Getter;

/**
 * 系统级异常 code。
 *
 * <p>当 {@link SystemTypeEnum#ERROR} 时，本字段标识具体异常类型。
 * <p>{@link #NONE} 是正常路径下的占位值 (code=0)。
 */
@Getter
public enum SystemErrorCodeEnum {

    NONE((byte) 0),
    /** request 序号不连续 (newSerialNum != lastSerialNum + 1) */
    SERIAL_NUM_NOT_CONTINUOUS((byte) 1),
    /** request 参数有误 */
    BAD_REQUEST_PARAM((byte) 2),
    /** 币对未开启交易 */
    SYMBOL_NOT_TRADING((byte) 3),
    /** 重复的订单下单 */
    DUPLICATE_ORDER((byte) 4);

    private final byte code;

    SystemErrorCodeEnum(byte code) {
        this.code = code;
    }

    public static SystemErrorCodeEnum of(byte code) {
        for (SystemErrorCodeEnum v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown SystemErrorCode code: " + code);
    }
}
