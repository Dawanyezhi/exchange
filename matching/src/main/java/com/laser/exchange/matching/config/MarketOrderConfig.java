package com.laser.exchange.matching.config;

import com.laser.exchange.common.utils.BigDecimalUtil;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Getter
@Configuration
public class MarketOrderConfig {

    public static final long DEFAULT_PROTECTION_BPS = 500L;
    public static final BigDecimal DEFAULT_MIN_TRADE_BASE_QTY = new BigDecimal("0.00000001");

    private final long protectionBps;
    private final BigDecimal minTradeBaseQty;

    public MarketOrderConfig(
            @Value("${laser.matching.market-order.protection-bps:500}") long protectionBps,
            @Value("${laser.matching.market-order.min-trade-base-qty:0.00000001}") BigDecimal minTradeBaseQty) {
        this.protectionBps = Math.max(0L, Math.min(10000L, protectionBps));
        this.minTradeBaseQty = minTradeBaseQty != null && minTradeBaseQty.signum() > 0
                ? minTradeBaseQty
                : DEFAULT_MIN_TRADE_BASE_QTY;
    }

    public static long normalizeProtectionBps(long protectionBps) {
        if (protectionBps <= 0L) {
            return 0L;
        }
        return Math.min(10000L, protectionBps);
    }

    public static BigDecimal normalizeMinTradeBaseQty(BigDecimal minTradeBaseQty) {
        if (minTradeBaseQty == null || minTradeBaseQty.signum() <= 0) {
            return DEFAULT_MIN_TRADE_BASE_QTY;
        }
        return BigDecimalUtil.formatBig(minTradeBaseQty, BigDecimalUtil.DEF_SCALE);
    }
}
