package com.laser.exchange.matching.benchmark;

import com.laser.exchange.common.enums.*;
import com.laser.exchange.matching.core.model.MatchOrderV1;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V1 订单生成器 — 使用 long 类型价格/数量
 */
public class OrderGeneratorV1 {

    public static final String SYMBOL = "SPOT_BTC_USDT";
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(1);
    private static final AtomicLong ACCOUNT_ID_SEQ = new AtomicLong(1000);

    public static void resetSequence() {
        ORDER_ID_SEQ.set(1);
        ACCOUNT_ID_SEQ.set(1000);
    }

    public static MatchOrderV1 limitBuy(long price, long quantity, TimeInForceEnum timeInForce) {
        return buildOrder(OrderType.LIMIT, OrderSideEnum.BUY, timeInForce, price, quantity);
    }

    public static MatchOrderV1 limitSell(long price, long quantity, TimeInForceEnum timeInForce) {
        return buildOrder(OrderType.LIMIT, OrderSideEnum.SELL, timeInForce, price, quantity);
    }

    public static MatchOrderV1 marketBuy(long quantity) {
        return buildOrder(OrderType.MARKET, OrderSideEnum.BUY, TimeInForceEnum.GTC, 0, quantity);
    }

    public static MatchOrderV1 marketSell(long quantity) {
        return buildOrder(OrderType.MARKET, OrderSideEnum.SELL, TimeInForceEnum.GTC, 0, quantity);
    }

    public static MatchOrderV1 limitBuyWithStp(long price, long quantity,
                                                TimeInForceEnum timeInForce,
                                                long accountId, long stpAccountId,
                                                StpStrategyEnum stpStrategy) {
        MatchOrderV1 order = buildOrder(OrderType.LIMIT, OrderSideEnum.BUY, timeInForce, price, quantity);
        order.setAccountId(accountId);
        order.setStpAccountId(stpAccountId);
        order.setStpStrategyEnum(stpStrategy);
        return order;
    }

    public static MatchOrderV1 limitSellWithStp(long price, long quantity,
                                                 TimeInForceEnum timeInForce,
                                                 long accountId, long stpAccountId,
                                                 StpStrategyEnum stpStrategy) {
        MatchOrderV1 order = buildOrder(OrderType.LIMIT, OrderSideEnum.SELL, timeInForce, price, quantity);
        order.setAccountId(accountId);
        order.setStpAccountId(stpAccountId);
        order.setStpStrategyEnum(stpStrategy);
        return order;
    }

    private static MatchOrderV1 buildOrder(OrderType orderType, OrderSideEnum side,
                                            TimeInForceEnum timeInForce,
                                            long price, long quantity) {
        long orderId = ORDER_ID_SEQ.getAndIncrement();
        long accountId = ACCOUNT_ID_SEQ.getAndIncrement();

        MatchOrderV1 order = new MatchOrderV1();
        order.setOrderId(orderId);
        order.setClientOid("bench-" + orderId);
        order.setAccountId(accountId);
        order.setSymbolId(SYMBOL);
        order.setOrderType(orderType);
        order.setOrderSide(side);
        order.setTimeInForce(timeInForce);
        order.setDelegatePrice(price);
        order.setDelegateCount(quantity);
        order.setDealtCount(0);
        order.setOrderStatus(OrderStatusEnum.NEW);
        order.setCreateTime(System.nanoTime());
        order.setUpdateTime(System.nanoTime());
        order.setStpAccountId(accountId);
        order.setStpStrategyEnum(StpStrategyEnum.DEFAULT);
        order.setCancelReason(CancelReasonEnum.NONE);
        return order;
    }
}
