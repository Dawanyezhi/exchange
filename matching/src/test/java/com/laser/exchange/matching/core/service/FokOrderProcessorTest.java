package com.laser.exchange.matching.core.service;

import com.laser.exchange.common.enums.*;
import com.laser.exchange.matching.core.engine.MatchEngine;
import com.laser.exchange.matching.core.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FokOrderProcessor#process 单元测试
 *
 * 测试场景覆盖:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ 1. 价格不交叉 → FOK_NOT_CROSS 撤单                                  │
 * │ 2. 预撮合阶段各分支:                                                │
 * │    ├─ 2.1 深度档位价格不再交叉 → 停止撮合                           │
 * │    ├─ 2.2 STP返回OP_BREAK(CANCEL_TAKER) → 终止撮合,FOK撤单          │
 * │    ├─ 2.3 STP返回OP_CONTINUE(CANCEL_MAKER) → 对手单撤销,继续        │
 * │    └─ 2.4 正常预撮合判断FOK能否完全成交                             │
 * │ 3. 预撮合后判断:                                                    │
 * │    ├─ FOK不能完全成交 → FOK_NOT_FULLFILL_CANCEL 撤单                │
 * │    └─ FOK能完全成交 → 进入真实撮合                                  │
 * │ 4. 真实撮合阶段:                                                    │
 * │    ├─ 单对手单完全成交                                              │
 * │    ├─ 多对手单完全成交(跨价格档位)                                  │
 * │    └─ 部分对手单成交(对手单部分成交)                                │
 * │ 5. 卖单FOK场景                                                      │
 * └─────────────────────────────────────────────────────────────────────┘
 */
public class FokOrderProcessorTest {

    private FokOrderProcessor fokOrderProcessor;
    private MatchEngine matchEngine;

    private static final String SYMBOL = "SPOT_BTC_USDT";

    @BeforeEach
    void setUp() {
        fokOrderProcessor = new FokOrderProcessor();
        matchEngine = new MatchEngine();

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(SYMBOL);

        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);
    }

    /**
     * 构建订单 - 使用默认accountId
     */
    private MatchOrder buildOrder(long orderId, OrderSideEnum side, TimeInForceEnum tif,
                                  StpStrategyEnum stpStrategy, long stpAccountId,
                                  BigDecimal price, BigDecimal count) {
        return buildOrderWithAccount(orderId, side, tif, stpStrategy, stpAccountId,
                stpAccountId, price, count);
    }

    /**
     * 构建订单 - 指定accountId和stpAccountId
     */
    private MatchOrder buildOrderWithAccount(long orderId, OrderSideEnum side, TimeInForceEnum tif,
                                              StpStrategyEnum stpStrategy, long accountId,
                                              long stpAccountId, BigDecimal price, BigDecimal count) {
        return MatchOrder.builder()
                .orderId(orderId)
                .symbolId(SYMBOL)
                .orderType(OrderType.LIMIT)
                .orderSide(side)
                .timeInForce(tif)
                .stpStrategyEnum(stpStrategy)
                .accountId(accountId)
                .stpAccountId(stpAccountId)
                .delegatePrice(price)
                .delegateCount(count)
                .dealtCount(BigDecimal.ZERO)
                .orderStatus(OrderStatusEnum.NEW)
                .build();
    }

    // ==================== 1. 价格不交叉场景 ====================

    @Test
    @DisplayName("CASE1-买单FOK价格低于卖盘最低价,不交叉撤单")
    void testFokBuyNotCross() {
        // 卖盘: 48900, 48999, 49000
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1"));
        MatchOrder sellOrder2 = buildOrder(1001L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("48999"), new BigDecimal("2"));
        MatchOrder sellOrder3 = buildOrder(1002L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("48900"), new BigDecimal("1"));

        matchEngine.placeOrder(sellOrder1);
        matchEngine.placeOrder(sellOrder2);
        matchEngine.placeOrder(sellOrder3);

        // FOK买单价格48000 < 卖盘最低价48900,不交叉
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("48000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        // 验证: FOK撤单,对手盘不变
        assertEquals(3, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.CANCELLED, fokOrder.getOrderStatus());
        assertEquals(CancelReasonEnum.FOK_NOT_CROSS, fokOrder.getCancelReason());
        assertEquals(OrderStatusEnum.NEW, sellOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, sellOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, sellOrder3.getOrderStatus());
    }

    @Test
    @DisplayName("CASE2-卖单FOK价格高于买盘最高价,不交叉撤单")
    void testFokSellNotCross() {
        // 买盘: 47000, 46500, 46000
        MatchOrder buyOrder1 = buildOrder(1000L, OrderSideEnum.BUY, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("47000"), new BigDecimal("1"));
        MatchOrder buyOrder2 = buildOrder(1001L, OrderSideEnum.BUY, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("46500"), new BigDecimal("2"));
        MatchOrder buyOrder3 = buildOrder(1002L, OrderSideEnum.BUY, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("46000"), new BigDecimal("1"));

        matchEngine.placeOrder(buyOrder1);
        matchEngine.placeOrder(buyOrder2);
        matchEngine.placeOrder(buyOrder3);

        // FOK卖单价格48000 > 买盘最高价47000,不交叉
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.SELL, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("48000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(3, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.CANCELLED, fokOrder.getOrderStatus());
        assertEquals(CancelReasonEnum.FOK_NOT_CROSS, fokOrder.getCancelReason());
    }

    @Test
    @DisplayName("CASE3-对手盘为空,不交叉撤单")
    void testFokEmptyOppositeBook() {
        // 没有任何对手盘订单
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.CANCELLED, fokOrder.getOrderStatus());
        assertEquals(CancelReasonEnum.FOK_NOT_CROSS, fokOrder.getCancelReason());
    }

    // ==================== 2. FOK无法完全成交场景 ====================

    @Test
    @DisplayName("CASE4-FOK订单量大于对手盘总量,无法完全成交撤单")
    void testFokNotFullFillCancel() {
        // 对手盘总量: 1 + 1.5 + 2 + 1 = 5.5
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1"));
        MatchOrder sellOrder2 = buildOrder(1001L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1.5"));
        MatchOrder sellOrder3 = buildOrder(1002L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("48999"), new BigDecimal("2"));
        MatchOrder sellOrder4 = buildOrder(1003L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("48900"), new BigDecimal("1"));

        matchEngine.placeOrder(sellOrder1);
        matchEngine.placeOrder(sellOrder2);
        matchEngine.placeOrder(sellOrder3);
        matchEngine.placeOrder(sellOrder4);

        // FOK买单量6 > 对手盘总量5.5
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49001"), new BigDecimal("6"));
        matchEngine.placeOrder(fokOrder);

        // 验证: FOK撤单,对手盘订单状态不变
        assertEquals(4, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.NEW, sellOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, sellOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, sellOrder3.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, sellOrder4.getOrderStatus());
        assertEquals(OrderStatusEnum.CANCELLED, fokOrder.getOrderStatus());
        assertEquals(CancelReasonEnum.FOK_NOT_FULLFILL_CANCEL, fokOrder.getCancelReason());
    }

    @Test
    @DisplayName("CASE5-FOK价格只能匹配部分档位,总量不足无法完全成交")
    void testFokPartialPriceLevelNotEnough() {
        // 只有48900档位的1个订单能被价格匹配
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1"));
        MatchOrder sellOrder2 = buildOrder(1001L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("48900"), new BigDecimal("1"));

        matchEngine.placeOrder(sellOrder1);
        matchEngine.placeOrder(sellOrder2);

        // FOK价格48950只能匹配48900档位(量=1),但FOK需要2
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("48950"), new BigDecimal("2"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(2, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.CANCELLED, fokOrder.getOrderStatus());
        assertEquals(CancelReasonEnum.FOK_NOT_FULLFILL_CANCEL, fokOrder.getCancelReason());
    }

    // ==================== 3. FOK完全成交场景 ====================

    @Test
    @DisplayName("CASE6-FOK与单个对手单完全成交")
    void testFokFullFillWithSingleOrder() {
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder.getOrderStatus());
        assertEquals(0, new BigDecimal("1").compareTo(fokOrder.getDealtCount()));
        assertEquals(0, new BigDecimal("1").compareTo(sellOrder.getDealtCount()));
    }

    @Test
    @DisplayName("CASE7-FOK与多个对手单完全成交(同一价格档位)")
    void testFokFullFillWithMultipleOrdersSamePrice() {
        // 同价格档位49000有3个订单,总量3.5
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1"));
        MatchOrder sellOrder2 = buildOrder(1001L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1.5"));
        MatchOrder sellOrder3 = buildOrder(1002L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1"));

        matchEngine.placeOrder(sellOrder1);
        matchEngine.placeOrder(sellOrder2);
        matchEngine.placeOrder(sellOrder3);

        // FOK买3.5,刚好吃完
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49000"), new BigDecimal("3.5"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder3.getOrderStatus());
    }

    @Test
    @DisplayName("CASE8-FOK跨多个价格档位完全成交")
    void testFokFullFillAcrossMultiplePriceLevels() {
        // 卖盘: 48900(1) + 48999(2) + 49000(2.5) = 5.5
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1"));
        MatchOrder sellOrder2 = buildOrder(1001L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1.5"));
        MatchOrder sellOrder3 = buildOrder(1002L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("48999"), new BigDecimal("2"));
        MatchOrder sellOrder4 = buildOrder(1003L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("48900"), new BigDecimal("1"));

        matchEngine.placeOrder(sellOrder1);
        matchEngine.placeOrder(sellOrder2);
        matchEngine.placeOrder(sellOrder3);
        matchEngine.placeOrder(sellOrder4);

        // FOK买5.5,刚好吃完所有对手单
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49001"), new BigDecimal("5.5"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder3.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder4.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE9-FOK成交后对手单部分成交")
    void testFokFullFillWithOpponentPartialFill() {
        // 卖单量5,FOK只买3
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("5"));
        matchEngine.placeOrder(sellOrder);

        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49000"), new BigDecimal("3"));
        matchEngine.placeOrder(fokOrder);

        // 卖单剩余2在orderBook中
        assertEquals(1, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
        assertEquals(OrderStatusEnum.PARTIALLY_FILLED, sellOrder.getOrderStatus());
        assertEquals(0, new BigDecimal("3").compareTo(fokOrder.getDealtCount()));
        assertEquals(0, new BigDecimal("3").compareTo(sellOrder.getDealtCount()));
        assertEquals(0, new BigDecimal("2").compareTo(sellOrder.getRemainingQuantity()));
    }

    // ==================== 4. STP自成交保护场景 ====================

    @Test
    @DisplayName("CASE10-STP策略CANCEL_TAKER,FOK直接撤单")
    void testFokWithStpCancelTaker() {
        // 同一账户下的对手单
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 100, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // FOK使用CANCEL_TAKER策略,同账户触发STP
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.CANCEL_TAKER, 100, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        // FOK撤单,对手单保留
        assertEquals(1, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.CANCELLED, fokOrder.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, sellOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE11-STP策略CANCEL_MAKER,对手单撤销后FOK成交")
    void testFokWithStpCancelMaker() {
        // 第一个卖单与FOK同账户,会被STP撤销
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 100, new BigDecimal("48900"), new BigDecimal("1"));
        // 第二个卖单不同账户,可以正常成交
        MatchOrder sellOrder2 = buildOrder(1001L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 200, new BigDecimal("49000"), new BigDecimal("1"));

        matchEngine.placeOrder(sellOrder1);
        matchEngine.placeOrder(sellOrder2);

        // FOK使用CANCEL_MAKER策略
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.CANCEL_MAKER, 100, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        // sellOrder1因STP被撤销,FOK与sellOrder2成交
        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.CANCELLED, sellOrder1.getOrderStatus());
        assertEquals(CancelReasonEnum.STP_CANCEL, sellOrder1.getCancelReason());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE12-STP策略CANCEL_MAKER,所有对手单都被撤销导致FOK无法成交")
    void testFokWithStpCancelMakerAllCancelled() {
        // 所有卖单都与FOK同账户
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 100, new BigDecimal("48900"), new BigDecimal("1"));
        MatchOrder sellOrder2 = buildOrder(1001L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 100, new BigDecimal("49000"), new BigDecimal("1"));

        matchEngine.placeOrder(sellOrder1);
        matchEngine.placeOrder(sellOrder2);

        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.CANCEL_MAKER, 100, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        // FOK预撮合阶段,对手单被标记STP撤销,但FOK无法完全成交,FOK撤单
        // 由于FOK预撮合失败,对手单实际不会被真正撤销(phase2不会执行)
        assertEquals(2, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.NEW, sellOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, sellOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.CANCELLED, fokOrder.getOrderStatus());
        assertEquals(CancelReasonEnum.FOK_NOT_FULLFILL_CANCEL, fokOrder.getCancelReason());
    }

    @Test
    @DisplayName("CASE13-STP策略CANCEL_BOTH,FOK直接撤单")
    void testFokWithStpCancelBoth() {
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 100, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // FOK使用CANCEL_BOTH策略,同账户触发STP
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.CANCEL_BOTH, 100, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        // CANCEL_BOTH对于FOK返回OP_BREAK,FOK撤单
        assertEquals(1, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.CANCELLED, fokOrder.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, sellOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE14-STP策略DEFAULT,不触发自成交保护正常成交")
    void testFokWithStpDefault() {
        // 同账户但使用DEFAULT策略,不会触发STP
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 100, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 100, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        // DEFAULT策略不拦截,正常成交
        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder.getOrderStatus());
    }

    // ==================== 5. 卖单FOK场景 ====================

    @Test
    @DisplayName("CASE15-卖单FOK完全成交")
    void testFokSellFullFill() {
        // 买盘
        MatchOrder buyOrder1 = buildOrder(1000L, OrderSideEnum.BUY, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("47000"), new BigDecimal("1"));
        MatchOrder buyOrder2 = buildOrder(1001L, OrderSideEnum.BUY, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("46500"), new BigDecimal("2"));

        matchEngine.placeOrder(buyOrder1);
        matchEngine.placeOrder(buyOrder2);

        // 卖单FOK价格46500,可匹配47000和46500档位,总量3
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.SELL, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("46500"), new BigDecimal("3"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, buyOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, buyOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE16-卖单FOK量不足无法完全成交")
    void testFokSellNotFullFill() {
        MatchOrder buyOrder1 = buildOrder(1000L, OrderSideEnum.BUY, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("47000"), new BigDecimal("1"));
        MatchOrder buyOrder2 = buildOrder(1001L, OrderSideEnum.BUY, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("46500"), new BigDecimal("1"));

        matchEngine.placeOrder(buyOrder1);
        matchEngine.placeOrder(buyOrder2);

        // 卖单FOK需要5,但买盘只有2
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.SELL, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("46500"), new BigDecimal("5"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(2, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.CANCELLED, fokOrder.getOrderStatus());
        assertEquals(CancelReasonEnum.FOK_NOT_FULLFILL_CANCEL, fokOrder.getCancelReason());
        assertEquals(OrderStatusEnum.NEW, buyOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, buyOrder2.getOrderStatus());
    }

    // ==================== 6. 边界场景 ====================

    @Test
    @DisplayName("CASE17-FOK数量刚好等于对手单数量")
    void testFokExactQuantityMatch() {
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1.123456"));
        matchEngine.placeOrder(sellOrder);

        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49000"), new BigDecimal("1.123456"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE18-FOK价格刚好等于对手单价格边界交叉")
    void testFokExactPriceCross() {
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // FOK价格刚好等于卖单价格
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE19-FOK小数量成交")
    void testFokSmallQuantity() {
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("0.00001"));
        matchEngine.placeOrder(sellOrder);

        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49000"), new BigDecimal("0.00001"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE20-FOK大数量成交")
    void testFokLargeQuantity() {
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("999999999"));
        matchEngine.placeOrder(sellOrder);

        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49000"), new BigDecimal("999999999"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
    }

    // ==================== 7. 复杂组合场景 ====================

    @Test
    @DisplayName("CASE21-STP部分撤销后剩余对手单足够FOK完全成交")
    void testFokWithPartialStpCancelMakerThenFullFill() {
        // 账户100的订单会被STP撤销
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 100, new BigDecimal("48800"), new BigDecimal("1"));
        // 账户200的订单可以正常成交
        MatchOrder sellOrder2 = buildOrder(1001L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 200, new BigDecimal("48900"), new BigDecimal("2"));
        MatchOrder sellOrder3 = buildOrder(1002L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 200, new BigDecimal("49000"), new BigDecimal("1"));

        matchEngine.placeOrder(sellOrder1);
        matchEngine.placeOrder(sellOrder2);
        matchEngine.placeOrder(sellOrder3);

        // FOK买2,使用CANCEL_MAKER,账户100的sellOrder1被撤销,与账户200的订单成交
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.CANCEL_MAKER, 100, new BigDecimal("49000"), new BigDecimal("2"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(1, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.CANCELLED, sellOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.NEW, sellOrder3.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE22-多价格档位部分成交验证")
    void testFokMultiplePriceLevelsPartialFill() {
        // 48900: 1, 48950: 1.5, 49000: 3
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("48900"), new BigDecimal("1"));
        MatchOrder sellOrder2 = buildOrder(1001L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("48950"), new BigDecimal("1.5"));
        MatchOrder sellOrder3 = buildOrder(1002L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("3"));

        matchEngine.placeOrder(sellOrder1);
        matchEngine.placeOrder(sellOrder2);
        matchEngine.placeOrder(sellOrder3);

        // FOK买3.5: 48900(1) + 48950(1.5) + 49000(1) = 3.5
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49000"), new BigDecimal("3.5"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(1, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.PARTIALLY_FILLED, sellOrder3.getOrderStatus());
        assertEquals(0, new BigDecimal("1").compareTo(sellOrder3.getDealtCount()));
        assertEquals(0, new BigDecimal("2").compareTo(sellOrder3.getRemainingQuantity()));
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
    }

    @Test
    @DisplayName("CASE23-连续多个FOK订单处理")
    void testMultipleFokOrders() {
        MatchOrder sellOrder1 = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.DEFAULT, 1, new BigDecimal("49000"), new BigDecimal("5"));
        matchEngine.placeOrder(sellOrder1);

        // 第一个FOK买2
        MatchOrder fokOrder1 = buildOrder(9998L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 2, new BigDecimal("49000"), new BigDecimal("2"));
        matchEngine.placeOrder(fokOrder1);

        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder1.getOrderStatus());
        assertEquals(OrderStatusEnum.PARTIALLY_FILLED, sellOrder1.getOrderStatus());
        assertEquals(0, new BigDecimal("3").compareTo(sellOrder1.getRemainingQuantity()));

        // 第二个FOK买2
        MatchOrder fokOrder2 = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 3, new BigDecimal("49000"), new BigDecimal("2"));
        matchEngine.placeOrder(fokOrder2);

        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder2.getOrderStatus());
        assertEquals(OrderStatusEnum.PARTIALLY_FILLED, sellOrder1.getOrderStatus());
        assertEquals(0, new BigDecimal("1").compareTo(sellOrder1.getRemainingQuantity()));

        // 第三个FOK买2,但剩余只有1
        MatchOrder fokOrder3 = buildOrder(10000L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.DEFAULT, 4, new BigDecimal("49000"), new BigDecimal("2"));
        matchEngine.placeOrder(fokOrder3);

        assertEquals(OrderStatusEnum.CANCELLED, fokOrder3.getOrderStatus());
        assertEquals(CancelReasonEnum.FOK_NOT_FULLFILL_CANCEL, fokOrder3.getCancelReason());
    }

    // ==================== 8. 不同账户场景 ====================

    @Test
    @DisplayName("CASE24-不同账户正常成交不触发STP")
    void testFokDifferentAccountsNoStp() {
        MatchOrder sellOrder = buildOrder(1000L, OrderSideEnum.SELL, TimeInForceEnum.GTC,
                StpStrategyEnum.CANCEL_TAKER, 100, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 不同账户,即使使用CANCEL_TAKER也不触发STP
        MatchOrder fokOrder = buildOrder(9999L, OrderSideEnum.BUY, TimeInForceEnum.FOK,
                StpStrategyEnum.CANCEL_TAKER, 200, new BigDecimal("49000"), new BigDecimal("1"));
        matchEngine.placeOrder(fokOrder);

        assertEquals(0, matchEngine.getMatchEngineState().getMatchContext()
                .getOrderBook(SYMBOL).getOrderMap().size());
        assertEquals(OrderStatusEnum.FULL_FILLED, fokOrder.getOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder.getOrderStatus());
    }
}
