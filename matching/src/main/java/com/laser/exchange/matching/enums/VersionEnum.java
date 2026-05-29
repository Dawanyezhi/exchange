package com.laser.exchange.matching.enums;

/**
 * 撮合引擎版本枚举
 *
 * V0: 原始版本，使用 BigDecimal 进行价格和数量的运算
 * V1: 优化版本，使用 long 替代 BigDecimal，消除对象分配与 GC 压力
 */
public enum VersionEnum {

    V0("原始版本 BigDecimal"),
    V1("优化版本 long");

    private final String description;

    VersionEnum(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
