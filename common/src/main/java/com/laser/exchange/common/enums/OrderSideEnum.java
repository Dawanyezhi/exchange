package com.laser.exchange.common.enums;

import org.agrona.collections.Int2ObjectHashMap;

import java.util.Map;

/**
 * 订单买卖方向
 */
public enum OrderSideEnum {

    BUY(1, "买单"),
    SELL(2, "卖单")
    ;

    OrderSideEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, OrderSideEnum> codeToEnum = new Int2ObjectHashMap<>();

    static {
        for (OrderSideEnum orderType : OrderSideEnum.values()) {
            codeToEnum.put(orderType.code, orderType);
        }
    }

    private int code;
    private String desc;

    public int getCode() {
        return code;
    }

    public static OrderSideEnum of(int code) {
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
