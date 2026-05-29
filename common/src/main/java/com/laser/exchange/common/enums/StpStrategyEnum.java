package com.laser.exchange.common.enums;

import org.agrona.collections.Int2ObjectHashMap;

import java.util.Map;

/**
 * 所谓的取消就是撤单
 */
public enum StpStrategyEnum {

    DEFAULT(0, "默认不拦截"),
    CANCEL_MAKER(1, "取消maker"),
    CANCEL_TAKER(2, "取消taker"),
    CANCEL_BOTH(3, "取消双向")
    ;

    StpStrategyEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, StpStrategyEnum> codeToEnum = new Int2ObjectHashMap<>();

    static {
        for (StpStrategyEnum orderType : StpStrategyEnum.values()) {
            codeToEnum.put(orderType.code, orderType);
        }
    }

    private int code;
    private String desc;

    public int getCode() {
        return code;
    }

    public static StpStrategyEnum of(int code) {
        return codeToEnum.get(code);
    }
}
