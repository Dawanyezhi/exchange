package com.laser.exchange.matching.core.model;

import com.laser.exchange.matching.config.MarketOrderConfig;
import lombok.Data;

/**
 * 撮合配置：币对维度
 */
@Data
public class MatchConfig {

    /**
     * 币对名称
     */
    private String symbol;

    /**
     * 是否可用/是否开启交易
     */
    private boolean enabled;

    /**
     * 市价单悬挂时间：一个市价单在行情不好，流动性差的情况下，最长的处理时间
     */
    private long marketOrderHangingTime;

    /**
     * 市价单保护比例，单位 bps（1 bps = 0.01%）。
     */
    private long marketOrderProtectionBps = MarketOrderConfig.DEFAULT_PROTECTION_BPS;
}
