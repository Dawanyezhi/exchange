package com.laser.exchange.common.config;

import lombok.Data;

/**
 * 币对配置，如btc-usdt
 */
@Data
public class SymbolConfig {

    /**
     * 币对id
     */
    private int symbolId;
    /**
     * 币对名称
     */
    private String symbolName;
    /**
     * 展示名称 可选
     */
    private String symbolDisplayName;
    /**
     * 交易币，如btc
     */
    private int baseCoinId;
    /**
     * 计价币 如usdt
     */
    private int quoteCoinId;
}
