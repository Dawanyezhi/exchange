package com.laser.exchange.common.enums;

import org.agrona.collections.Int2ObjectHashMap;

import java.util.Map;

/**
 * 市价单目标单位。
 *
 * <p>BASE_QTY 表示订单目标按基础币数量表达，例如买/卖 1 BTC。
 * QUOTE_AMOUNT 表示订单目标按计价币金额表达，例如花 100 USDT 买入，或卖出以获得 100 USDT。
 */
public enum MarketTargetTypeEnum {

    BASE_QTY(1, "基础币数量"),
    QUOTE_AMOUNT(2, "计价币金额");

    private static final Map<Integer, MarketTargetTypeEnum> codeToEnum = new Int2ObjectHashMap<>();

    static {
        for (MarketTargetTypeEnum targetType : MarketTargetTypeEnum.values()) {
            codeToEnum.put(targetType.code, targetType);
        }
    }

    private final int code;
    private final String desc;

    MarketTargetTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static MarketTargetTypeEnum of(int code) {
        return codeToEnum.get(code);
    }

    public static MarketTargetTypeEnum ofName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return MarketTargetTypeEnum.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
