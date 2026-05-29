package com.laser.exchange.common.enums;

import org.agrona.collections.Int2ObjectHashMap;

import java.util.Map;

/**
 * 订单类型
 */
public enum OrderType {

    LIMIT(1, "限价单"),
    MARKET(2, "市价单")
    ;

    OrderType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, OrderType> codeToEnum = new Int2ObjectHashMap<>();

    static {
        for (OrderType orderType : OrderType.values()) {
            codeToEnum.put(orderType.code, orderType);
        }
    }

    private int code;
    private String desc;

    public int getCode() {
        return code;
    }

    public static OrderType of(int code) {
        return codeToEnum.get(code);
    }

    /**
     * 校验类型合法
     * @param code
     * @return
     */
    public boolean isValidOrderType(int code) {
        return codeToEnum.containsKey(code);
    }
}
