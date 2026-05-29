package com.laser.exchange.common.enums;

import org.agrona.collections.Int2ObjectHashMap;

import java.util.Map;

/**
 * 订单状态
 */
public enum OrderStatusEnum {

    NEW(1, false, "新建订单"),
    CANCELLED(2,  true, "撤销"),
    PARTIALLY_FILLED(3, false,"部分成交"),
    FULL_FILLED(4, true, "完全成交"),
    REJECTED(5, true, "拒绝 (订单无效、重复订单、币对未配置等) ")
    ;

    OrderStatusEnum(int code, boolean over, String desc) {
        this.code = code;
        this.over = over;
        this.desc = desc;
    }

    private static final Map<Integer, OrderStatusEnum> codeToEnum = new Int2ObjectHashMap<>();

    static {
        for (OrderStatusEnum orderType : OrderStatusEnum.values()) {
            codeToEnum.put(orderType.code, orderType);
        }
    }

    private int code;
    private boolean over;
    private String desc;

    public static OrderStatusEnum of(int code) {
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

    public boolean over() {
        return this.over;
    }

    public int getCode() {
        return this.code;
    }
}
