package com.laser.exchange.matching.benchmark;

import com.laser.exchange.common.enums.*;
import com.laser.exchange.matching.core.model.MatchOrder;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单生成器 — 为 Benchmark 场景快速构造 MatchOrder。
 *
 * <p>所有生成的订单默认状态为 {@link OrderStatusEnum#NEW}，
 * 成交数量为 0，取消原因为 {@link CancelReasonEnum#NONE}。</p>
 *
 * <h3>典型用法：</h3>
 * <pre>
 *   // 生成一个 GTC 限价买单
 *   MatchOrder order = OrderGenerator.limitBuy(49000, 1.5, TimeInForceEnum.GTC);
 *
 *   // 生成一个市价卖单
 *   MatchOrder market = OrderGenerator.marketSell(2.0);
 * </pre>
 */
public class OrderGenerator {

    /** 基准币对，所有 benchmark 订单使用同一币对 */
    public static final String SYMBOL = "SPOT_BTC_USDT";

    /** 订单 ID 自增器，保证唯一性 */
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(1);

    /** 账户 ID 自增器 */
    private static final AtomicLong ACCOUNT_ID_SEQ = new AtomicLong(1000);

    /**
     * 重置 ID 序列（在每次 benchmark 迭代前调用，避免 long 溢出）
     */
    public static void resetSequence() {
        ORDER_ID_SEQ.set(1);
        ACCOUNT_ID_SEQ.set(1000);
    }

    /**
     * 构造一个限价买单
     *
     * @param price       委托价格
     * @param quantity    委托数量
     * @param timeInForce 生效类型（GTC/IOC/FOK/POST_ONLY）
     * @return 新建的限价买单
     */
    public static MatchOrder limitBuy(double price, double quantity, TimeInForceEnum timeInForce) {
        return buildOrder(OrderType.LIMIT, OrderSideEnum.BUY, timeInForce, price, quantity);
    }

    /**
     * 构造一个限价卖单
     *
     * @param price       委托价格
     * @param quantity    委托数量
     * @param timeInForce 生效类型
     * @return 新建的限价卖单
     */
    public static MatchOrder limitSell(double price, double quantity, TimeInForceEnum timeInForce) {
        return buildOrder(OrderType.LIMIT, OrderSideEnum.SELL, timeInForce, price, quantity);
    }

    /**
     * 构造一个市价买单
     *
     * @param quantity 委托数量
     * @return 新建的市价买单
     */
    public static MatchOrder marketBuy(double quantity) {
        return buildOrder(OrderType.MARKET, OrderSideEnum.BUY, TimeInForceEnum.GTC, 0, quantity);
    }

    /**
     * 构造一个市价卖单
     *
     * @param quantity 委托数量
     * @return 新建的市价卖单
     */
    public static MatchOrder marketSell(double quantity) {
        return buildOrder(OrderType.MARKET, OrderSideEnum.SELL, TimeInForceEnum.GTC, 0, quantity);
    }

    /**
     * 构造一个指定账户的限价买单（用于 STP 自成交保护场景）
     *
     * @param price        委托价格
     * @param quantity     委托数量
     * @param timeInForce  生效类型
     * @param accountId    账户 ID
     * @param stpAccountId STP 账户 ID
     * @param stpStrategy  STP 策略
     * @return 带有 STP 配置的限价买单
     */
    public static MatchOrder limitBuyWithStp(double price, double quantity,
                                             TimeInForceEnum timeInForce,
                                             long accountId, long stpAccountId,
                                             StpStrategyEnum stpStrategy) {
        MatchOrder order = buildOrder(OrderType.LIMIT, OrderSideEnum.BUY, timeInForce, price, quantity);
        order.setAccountId(accountId);
        order.setStpAccountId(stpAccountId);
        order.setStpStrategyEnum(stpStrategy);
        return order;
    }

    /**
     * 构造一个指定账户的限价卖单（用于 STP 自成交保护场景）
     */
    public static MatchOrder limitSellWithStp(double price, double quantity,
                                              TimeInForceEnum timeInForce,
                                              long accountId, long stpAccountId,
                                              StpStrategyEnum stpStrategy) {
        MatchOrder order = buildOrder(OrderType.LIMIT, OrderSideEnum.SELL, timeInForce, price, quantity);
        order.setAccountId(accountId);
        order.setStpAccountId(stpAccountId);
        order.setStpStrategyEnum(stpStrategy);
        return order;
    }

    /**
     * 核心构造方法：组装 MatchOrder 所有字段
     */
    private static MatchOrder buildOrder(OrderType orderType, OrderSideEnum side,
                                         TimeInForceEnum timeInForce,
                                         double price, double quantity) {
        long orderId = ORDER_ID_SEQ.getAndIncrement();
        long accountId = ACCOUNT_ID_SEQ.getAndIncrement();

        MatchOrder order = new MatchOrder();
        order.setOrderId(orderId);
        order.setClientOid("bench-" + orderId);
        order.setAccountId(accountId);
        order.setSymbolId(SYMBOL);
        order.setOrderType(orderType);
        order.setOrderSide(side);
        order.setTimeInForce(timeInForce);
        order.setDelegatePrice(BigDecimal.valueOf(price));
        order.setDelegateCount(BigDecimal.valueOf(quantity));
        order.setDealtCount(BigDecimal.ZERO);
        order.setOrderStatus(OrderStatusEnum.NEW);
        order.setCreateTime(System.nanoTime());
        order.setUpdateTime(System.nanoTime());
        order.setStpAccountId(accountId);
        order.setStpStrategyEnum(StpStrategyEnum.DEFAULT);
        order.setCancelReason(CancelReasonEnum.NONE);
        return order;
    }
}
