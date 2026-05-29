package com.laser.exchange.common.enums;

import lombok.Getter;

/**
 * 宏观结果类型。
 *
 * <ul>
 *   <li>{@link #NORMAL}: 正常的处理结果</li>
 *   <li>{@link #ERROR}: 异常情况，需结合 {@link SystemErrorCodeEnum} 使用</li>
 * </ul>
 */
@Getter
public enum SystemTypeEnum {

    NORMAL((byte) 1),
    ERROR((byte) 2);

    private final byte code;

    SystemTypeEnum(byte code) {
        this.code = code;
    }

    public static SystemTypeEnum of(byte code) {
        for (SystemTypeEnum v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown SystemType code: " + code);
    }
}
