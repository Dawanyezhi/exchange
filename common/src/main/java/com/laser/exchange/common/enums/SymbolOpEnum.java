package com.laser.exchange.common.enums;

import lombok.Getter;

/**
 * 上下币操作类型。
 *
 * <ul>
 *   <li>{@link #LIST} 上币 (op=1)：注册新 symbolConfig + 默认 disabled 的 matchConfig</li>
 *   <li>{@link #DELIST} 下币 (op=2)：移除 symbolConfig + matchConfig.enabled=false</li>
 * </ul>
 */
@Getter
public enum SymbolOpEnum {

    LIST((byte) 1),
    DELIST((byte) 2);

    private final byte code;

    SymbolOpEnum(byte code) {
        this.code = code;
    }

    public static SymbolOpEnum of(byte code) {
        for (SymbolOpEnum v : values()) {
            if (v.code == code) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown SymbolOp code: " + code);
    }
}
