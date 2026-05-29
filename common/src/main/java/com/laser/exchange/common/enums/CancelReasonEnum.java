package com.laser.exchange.common.enums;

import org.agrona.collections.Int2ObjectHashMap;

import java.util.Map;

public enum CancelReasonEnum {
    NONE(0, "none", "默认"),
    POST_ONLY_CROSS(1, "postOnly order's price shouldn't cross", "postOnly订单发生价格交叉撤单"),
    IOC_NOT_CROSS(2, "ioc order's price should cross", "ioc订单价格不交叉撤单"),
    FOK_NOT_CROSS(3, "fok order's price should cross", "fok订单价格不交叉撤单"),
    STP_CANCEL(4, "stp cancel maker order", "自成交保护撤挂单"),
    IOC_NOT_FULLFILL_CANCEL_REMAINING(5, "ioc order not fullfill then cancel remaining.", "ioc订单没有完全成交，剩余撤单"),
    FOK_NOT_FULLFILL_CANCEL(6, "fok order can not full fill. cancel", "fok订单不能完全成交，撤单"),
    ;

    CancelReasonEnum(int code, String remark, String desc) {
        this.code = code;
        this.remark = remark;
        this.desc = desc;
    }

    private static final Map<Integer, CancelReasonEnum> codeToEnum = new Int2ObjectHashMap<>();

    static {
        for (CancelReasonEnum orderType : CancelReasonEnum.values()) {
            codeToEnum.put(orderType.code, orderType);
        }
    }

    private int code;
    private String remark;
    private String desc;

    public static CancelReasonEnum of(int code) {
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

    public int getCode() {
        return this.code;
    }
}
