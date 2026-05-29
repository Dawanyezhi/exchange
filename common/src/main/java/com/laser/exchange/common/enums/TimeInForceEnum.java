package com.laser.exchange.common.enums;

import org.agrona.collections.Int2ObjectHashMap;

import java.util.Map;

/**
 * 订单生效类型
 */
public enum TimeInForceEnum {

    GTC(1, "一直生效直到订单撤销, 普通限价单、市价单"),
    IOC(2, "immediate or cancel, 立即成交剩余部分进行撤销"),
    FOK(3, "fill or kill, 全部成交或者全部撤销"),
    POST_ONLY(4, "只做挂单, 如果交叉直接撤单，否则作为挂单进入深度. 往往被做市商大量采用, 流动性的提供者")
    ;

    TimeInForceEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    private static final Map<Integer, TimeInForceEnum> codeToEnum = new Int2ObjectHashMap<>();

    static {
        for (TimeInForceEnum orderType : TimeInForceEnum.values()) {
            codeToEnum.put(orderType.code, orderType);
        }
    }

    private int code;
    private String desc;

    public int getCode() {
        return code;
    }

    public static TimeInForceEnum of(int code) {
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
