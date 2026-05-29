package com.laser.exchange.matching.core.engine;

import com.laser.exchange.common.enums.*;
import com.laser.exchange.matching.core.model.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Assertions;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class MatchEngineTest {

    private MatchEngine matchEngine;
    private MatchEngineState matchEngineState;

    private static final String SCENE = "placeOrder";

    @BeforeEach
    void setUp() throws Exception {
        matchEngine = new MatchEngine();
        // 通过反射获取内部状态，用于测试前置数据准备
        Field stateField = MatchEngine.class.getDeclaredField("matchEngineState");
        stateField.setAccessible(true);
        matchEngineState = (MatchEngineState) stateField.get(matchEngine);
    }

    private MatchConfig createEnabledConfig(String symbol) {
        MatchConfig config = new MatchConfig();
        config.setSymbol(symbol);
        config.setEnabled(true);
        return config;
    }

    private MatchConfig createDisabledConfig(String symbol) {
        MatchConfig config = new MatchConfig();
        config.setSymbol(symbol);
        config.setEnabled(false);
        return config;
    }

    private MatchOrder buildLimitOrder(long orderId, String symbol, OrderSideEnum side,
                                       TimeInForceEnum tif, BigDecimal price, BigDecimal qty) {
        return MatchOrder.builder()
                .orderId(orderId)
                .symbolId(symbol)
                .orderType(OrderType.LIMIT)
                .orderSide(side)
                .timeInForce(tif)
                .delegatePrice(price)
                .delegateCount(qty)
                .orderStatus(OrderStatusEnum.NEW)
                .build();
    }

    // ==================== checkMatchConfig ====================

    @Test
    @DisplayName("币对未配置时，应拒绝下单")
    void checkMatchConfig_noConfig_shouldReject() {
        assertTrue(matchEngine.checkMatchConfig("SPOT_BTC_USDT"));
    }

    @Test
    @DisplayName("币对已配置但未启用时，应拒绝下单")
    void checkMatchConfig_disabled_shouldReject() {
        matchEngineState.addMatchConfig(createDisabledConfig("SPOT_BTC_USDT"));
        assertTrue(matchEngine.checkMatchConfig("SPOT_BTC_USDT"));
    }

    @Test
    @DisplayName("币对已配置且启用时，应允许下单")
    void checkMatchConfig_enabled_shouldAllow() {
        matchEngineState.addMatchConfig(createEnabledConfig("SPOT_BTC_USDT"));
        assertFalse(matchEngine.checkMatchConfig("SPOT_BTC_USDT"));
    }

    // ==================== placeOrder ====================

    @Test
    @DisplayName("下单时币对未配置，订单不应进入订单簿")
    void placeOrder_noConfig_shouldNotCreateOrderBook() {
        MatchOrder order = buildLimitOrder(1001L, "SPOT_BTC_USDT", OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));

        matchEngine.placeOrder(order);

        assertNull(matchEngineState.getMatchContext().getOrderBook("SPOT_BTC_USDT"));
    }

    @Test
    @DisplayName("下单时币对已启用，应自动创建订单簿")
    void placeOrder_enabled_shouldCreateOrderBook() {
        matchEngineState.addMatchConfig(createEnabledConfig("SPOT_BTC_USDT"));

        MatchOrder order = buildLimitOrder(1001L, "SPOT_BTC_USDT", OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));

        matchEngine.placeOrder(order);

        assertNotNull(matchEngineState.getMatchContext().getOrderBook("SPOT_BTC_USDT"));
    }

    // ==================== processLimitOrder ====================

    @Test
    @DisplayName("PostOnly买单 - 无对手盘时应挂单成功")
    void processPostOnly_noOpposite_shouldAddToBook() {
        String symbol = "SPOT_BTC_USDT";
        OrderBook orderBook = new OrderBook(symbol);

        MatchOrder buyOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.POST_ONLY, new BigDecimal("50000"), new BigDecimal("1"));

        matchEngine.processLimitOrder(buyOrder, orderBook);

        assertNotNull(orderBook.getOrder(1001L));
    }

    @Test
    @DisplayName("PostOnly买单 - 与卖盘价格交叉时应撤单")
    void processPostOnly_crossWithAsk_shouldReject() {
        String symbol = "SPOT_BTC_USDT";
        OrderBook orderBook = new OrderBook(symbol);

        // 先挂一个卖单到订单簿，价格49000
        MatchOrder sellOrder = buildLimitOrder(1000L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("49000"), new BigDecimal("1"));
        orderBook.addOrder(sellOrder);

        // 再下一个PostOnly买单，价格50000（>= 卖盘最优价49000，交叉）
        MatchOrder buyOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.POST_ONLY, new BigDecimal("50000"), new BigDecimal("1"));

        matchEngine.processLimitOrder(buyOrder, orderBook);

        assertEquals(OrderStatusEnum.CANCELLED, buyOrder.getOrderStatus());
        assertNull(orderBook.getOrder(1001L));
    }

    @Test
    @DisplayName("PostOnly卖单 - 与买盘价格交叉时应撤单")
    void processPostOnly_crossWithBid_shouldReject() {
        String symbol = "SPOT_BTC_USDT";
        OrderBook orderBook = new OrderBook(symbol);

        // 先挂一个买单，价格50000
        MatchOrder buyOrder = buildLimitOrder(1000L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        orderBook.addOrder(buyOrder);

        // 再下一个PostOnly卖单，价格49000（<= 买盘最优价50000，交叉）
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.POST_ONLY, new BigDecimal("49000"), new BigDecimal("1"));

        matchEngine.processLimitOrder(sellOrder, orderBook);

        assertEquals(OrderStatusEnum.CANCELLED, sellOrder.getOrderStatus());
        assertNull(orderBook.getOrder(1001L));
    }

    @Test
    @DisplayName("PostOnly卖单 - 无交叉时应挂单成功")
    void processPostOnly_noCross_shouldAddToBook() {
        String symbol = "SPOT_BTC_USDT";
        OrderBook orderBook = new OrderBook(symbol);

        // 先挂一个买单，价格49000
        MatchOrder buyOrder = buildLimitOrder(1000L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("49000"), new BigDecimal("1"));
        orderBook.addOrder(buyOrder);

        // 再下一个PostOnly卖单，价格50000（> 买盘最优价49000，不交叉）
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.POST_ONLY, new BigDecimal("50000"), new BigDecimal("1"));

        matchEngine.processLimitOrder(sellOrder, orderBook);

        assertNotNull(orderBook.getOrder(1001L));
    }

    @Test
    @DisplayName("postonly价格交叉")
    void testPostOnlyCross() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 模拟下买单的情况
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 打印订单簿情况（有一个卖单）
        OrderBook book = engineState.getMatchContext().getOrderBook(symbol);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(1, book.getOrderMap().size());

        // 下postonly买单
        MatchOrder postOnlyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.POST_ONLY, new BigDecimal("50001"), new BigDecimal("2"));
        matchEngine.placeOrder(postOnlyOrder);

        // 撤单
        Assertions.assertEquals(OrderStatusEnum.CANCELLED, postOnlyOrder.getOrderStatus());
        log.info("orderStatus:{}", postOnlyOrder.getOrderStatus());
        book.printOrderBook(SCENE);
    }

    @Test
    @DisplayName("postonly价格不交叉 下到订单簿")
    void testPostOnlyNotCrossPlaceSuccess() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 打印订单簿情况（有一个卖单）
        OrderBook book = engineState.getMatchContext().getOrderBook(symbol);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(1, book.getOrderMap().size());

        // 下postonly买单
        MatchOrder postOnlyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.POST_ONLY, new BigDecimal("49999.99"), new BigDecimal("2"));
        matchEngine.placeOrder(postOnlyOrder);

        Assertions.assertEquals(2, book.getOrderMap().size());
        Assertions.assertEquals(OrderStatusEnum.NEW, postOnlyOrder.getOrderStatus());
        book.printOrderBook(SCENE);
    }

    @Test
    @DisplayName("正常下gtc没有撮合")
    void testPlaceGtcWithoutMatching() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 打印订单簿情况（有一个卖单）
        OrderBook book = engineState.getMatchContext().getOrderBook(symbol);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(1, book.getOrderMap().size());

        // 下订单2
        MatchOrder buyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("49999"), new BigDecimal("1"));
        matchEngine.placeOrder(buyOrder);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(2, book.getOrderMap().size());

        Assertions.assertEquals(OrderStatusEnum.NEW, buyOrder.getOrderStatus());
    }

    @Test
    @DisplayName("正常下gtc完全成交")
    void testPlaceGtcFullFill() {

        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 打印订单簿情况（有一个卖单）
        OrderBook book = engineState.getMatchContext().getOrderBook(symbol);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(1, book.getOrderMap().size());

        // 下订单2
        MatchOrder buyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("50000.1"), new BigDecimal("1"));
        matchEngine.placeOrder(buyOrder);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(0, book.getOrderMap().size());

        Assertions.assertEquals(OrderStatusEnum.FULL_FILLED, buyOrder.getOrderStatus());

    }

    @Test
    @DisplayName("正常下gtc成交部分剩余挂单")
    void testPlaceGtcPartialFillPlaceRemaining() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 打印订单簿情况（有一个卖单）
        OrderBook book = engineState.getMatchContext().getOrderBook(symbol);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(1, book.getOrderMap().size());

        // 下订单2
        MatchOrder buyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("50000.1"), new BigDecimal("2.54"));
        matchEngine.placeOrder(buyOrder);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(1, book.getOrderMap().size());
        Assertions.assertEquals(OrderStatusEnum.PARTIALLY_FILLED, buyOrder.getOrderStatus());
        Assertions.assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder.getOrderStatus());
        Assertions.assertEquals(new BigDecimal("1.54"), buyOrder.getRemainingQuantity());
    }

    @Test
    @DisplayName("ioc-没有交叉直接撤单")
    void testIocNotCrossCancel() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 打印订单簿情况（有一个卖单）
        OrderBook book = engineState.getMatchContext().getOrderBook(symbol);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(1, book.getOrderMap().size());

        // 下IOC订单
        MatchOrder buyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.IOC, new BigDecimal("49999"), new BigDecimal("2.54"));
        matchEngine.placeOrder(buyOrder);

        Assertions.assertEquals(1, book.getOrderMap().size());
        Assertions.assertEquals(OrderStatusEnum.CANCELLED, buyOrder.getOrderStatus());
    }

    @Test
    @DisplayName("ioc-交叉完全成交")
    void testIocCrossFullFill() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 打印订单簿情况（有一个卖单）
        OrderBook book = engineState.getMatchContext().getOrderBook(symbol);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(1, book.getOrderMap().size());

        // 下IOC订单
        MatchOrder buyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.IOC, new BigDecimal("50001"), new BigDecimal("0.9"));
        matchEngine.placeOrder(buyOrder);
        book.printOrderBook(SCENE);

        Assertions.assertEquals(1, book.getOrderMap().size());
        Assertions.assertEquals(OrderStatusEnum.FULL_FILLED, buyOrder.getOrderStatus());
    }

    @Test
    @DisplayName("ioc-交叉部分成交剩余撤销")
    void testIocCrossPartiallyFillCancelRemaining() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 打印订单簿情况（有一个卖单）
        OrderBook book = engineState.getMatchContext().getOrderBook(symbol);
        book.printOrderBook(SCENE);
        Assertions.assertEquals(1, book.getOrderMap().size());

        // 下IOC订单
        MatchOrder buyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.IOC, new BigDecimal("50001"), new BigDecimal("1.5"));
        matchEngine.placeOrder(buyOrder);
        book.printOrderBook(SCENE);

        Assertions.assertEquals(0, book.getOrderMap().size());
        Assertions.assertEquals(OrderStatusEnum.CANCELLED, buyOrder.getOrderStatus());
    }

    // ==================== processGtcOrder 完整单元测试覆盖 ====================

    @Nested
    @DisplayName("GTC订单 - processGtcOrder 完整覆盖")
    class ProcessGtcOrderTest {

        private static final String SYMBOL = "SPOT_BTC_USDT";
        private OrderBook orderBook;

        @BeforeEach
        void initOrderBook() {
            orderBook = new OrderBook(SYMBOL);
        }

        // ---------- 无交叉，直接挂单 ----------

        @Test
        @DisplayName("买单 - 空订单簿，无对手盘，直接挂单")
        void gtc_buy_emptyBook_shouldAddToBook() {
            MatchOrder buy = buildLimitOrder(1L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertNotNull(orderBook.getOrder(1L));
            assertEquals(OrderStatusEnum.NEW, buy.getOrderStatus());
            assertEquals(1, orderBook.getBuyOrders().size());
            assertEquals(0, orderBook.getSellOrders().size());
        }

        @Test
        @DisplayName("卖单 - 空订单簿，无对手盘，直接挂单")
        void gtc_sell_emptyBook_shouldAddToBook() {
            MatchOrder sell = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));

            matchEngine.processLimitOrder(sell, orderBook);

            assertNotNull(orderBook.getOrder(1L));
            assertEquals(OrderStatusEnum.NEW, sell.getOrderStatus());
            assertEquals(0, orderBook.getBuyOrders().size());
            assertEquals(1, orderBook.getSellOrders().size());
        }

        @Test
        @DisplayName("买单 - 价格低于最优卖价，无交叉，直接挂单")
        void gtc_buy_priceBelowBestAsk_shouldAddToBook() {
            // 卖盘挂单 @50000
            orderBook.addOrder(buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1")));

            // 买单 @49999，低于卖盘最优价，不交叉
            MatchOrder buy = buildLimitOrder(2L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("49999"), new BigDecimal("1"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(2, orderBook.getOrderMap().size());
            assertEquals(OrderStatusEnum.NEW, buy.getOrderStatus());
            assertEquals(1, orderBook.getBuyOrders().size());
        }

        @Test
        @DisplayName("卖单 - 价格高于最优买价，无交叉，直接挂单")
        void gtc_sell_priceAboveBestBid_shouldAddToBook() {
            // 买盘挂单 @49000
            orderBook.addOrder(buildLimitOrder(1L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("49000"), new BigDecimal("1")));

            // 卖单 @50000，高于买盘最优价，不交叉
            MatchOrder sell = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));

            matchEngine.processLimitOrder(sell, orderBook);

            assertEquals(2, orderBook.getOrderMap().size());
            assertEquals(OrderStatusEnum.NEW, sell.getOrderStatus());
            assertEquals(1, orderBook.getSellOrders().size());
        }

        // ---------- 交叉，完全成交 ----------

        @Test
        @DisplayName("买单 - 与单个卖单等量交叉，双方完全成交，订单簿清空")
        void gtc_buy_exactMatchSingleSell_bothFullFilled() {
            MatchOrder sell = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("3"));
            orderBook.addOrder(sell);

            MatchOrder buy = buildLimitOrder(2L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("3"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, sell.getOrderStatus());
            assertEquals(0, orderBook.getOrderMap().size());
            assertTrue(orderBook.getSellOrders().isEmpty());
            assertTrue(orderBook.getBuyOrders().isEmpty());
        }

        @Test
        @DisplayName("卖单 - 与单个买单等量交叉，双方完全成交")
        void gtc_sell_exactMatchSingleBuy_bothFullFilled() {
            MatchOrder buy = buildLimitOrder(1L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("2"));
            orderBook.addOrder(buy);

            MatchOrder sell = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("2"));

            matchEngine.processLimitOrder(sell, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, sell.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(0, orderBook.getOrderMap().size());
        }

        @Test
        @DisplayName("买单 - taker数量 < maker数量，taker完全成交，maker部分成交留在簿上")
        void gtc_buy_takerQtyLessThanMaker_takerFullFilledMakerPartial() {
            MatchOrder sell = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("10"));
            orderBook.addOrder(sell);

            MatchOrder buy = buildLimitOrder(2L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("3"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(OrderStatusEnum.PARTIALLY_FILLED, sell.getOrderStatus());
            assertEquals(new BigDecimal("7"), sell.getRemainingQuantity());
            assertEquals(1, orderBook.getOrderMap().size());
            assertNotNull(orderBook.getOrder(1L));
        }

        // ---------- 交叉，部分成交后剩余挂单 ----------

        @Test
        @DisplayName("买单 - taker数量 > maker数量，maker完全成交被移除，taker剩余挂到买盘")
        void gtc_buy_takerQtyGreaterThanMaker_remainderAddedToBook() {
            MatchOrder sell = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(sell);

            MatchOrder buy = buildLimitOrder(2L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("5"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, sell.getOrderStatus());
            assertEquals(OrderStatusEnum.PARTIALLY_FILLED, buy.getOrderStatus());
            assertEquals(new BigDecimal("4"), buy.getRemainingQuantity());
            assertEquals(1, orderBook.getOrderMap().size());
            assertNotNull(orderBook.getOrder(2L));
            assertEquals(1, orderBook.getBuyOrders().size());
            assertTrue(orderBook.getSellOrders().isEmpty());
        }

        @Test
        @DisplayName("卖单 - taker数量 > maker数量，maker完全成交被移除，taker剩余挂到卖盘")
        void gtc_sell_takerQtyGreaterThanMaker_remainderAddedToBook() {
            MatchOrder buy = buildLimitOrder(1L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("2"));
            orderBook.addOrder(buy);

            MatchOrder sell = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("5"));

            matchEngine.processLimitOrder(sell, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(OrderStatusEnum.PARTIALLY_FILLED, sell.getOrderStatus());
            assertEquals(new BigDecimal("3"), sell.getRemainingQuantity());
            assertEquals(1, orderBook.getOrderMap().size());
            assertNotNull(orderBook.getOrder(2L));
            assertTrue(orderBook.getBuyOrders().isEmpty());
            assertEquals(1, orderBook.getSellOrders().size());
        }

        // ---------- 跨多个价格档位撮合 ----------

        @Test
        @DisplayName("买单 - 吃穿多个卖盘价格档位，全部成交")
        void gtc_buy_sweepMultiplePriceLevels_fullFilled() {
            // 卖盘: @50000 x 1, @50100 x 2, @50200 x 1
            MatchOrder s1 = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            MatchOrder s2 = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("2"));
            MatchOrder s3 = buildLimitOrder(3L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50200"), new BigDecimal("1"));
            orderBook.addOrder(s1);
            orderBook.addOrder(s2);
            orderBook.addOrder(s3);
            assertEquals(3, orderBook.getSellOrders().size());

            // 买单 @50200 x 4，能吃掉全部三个档位
            MatchOrder buy = buildLimitOrder(10L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50200"), new BigDecimal("4"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s1.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s2.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s3.getOrderStatus());
            assertEquals(0, orderBook.getOrderMap().size());
            assertTrue(orderBook.getSellOrders().isEmpty());
        }

        @Test
        @DisplayName("买单 - 吃穿部分档位后剩余，剩余挂到买盘")
        void gtc_buy_sweepPartialLevels_remainderAdded() {
            // 卖盘: @50000 x 1, @50100 x 2
            orderBook.addOrder(buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1")));
            orderBook.addOrder(buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("2")));

            // 买单 @50200 x 5，吃掉全部卖盘(3)后还剩2
            MatchOrder buy = buildLimitOrder(10L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50200"), new BigDecimal("5"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.PARTIALLY_FILLED, buy.getOrderStatus());
            assertEquals(new BigDecimal("2"), buy.getRemainingQuantity());
            assertTrue(orderBook.getSellOrders().isEmpty());
            assertEquals(1, orderBook.getBuyOrders().size());
            assertEquals(1, orderBook.getOrderMap().size());
        }

        @Test
        @DisplayName("买单 - 只能吃到部分档位，价格不够高的档位不撮合")
        void gtc_buy_cannotReachAllLevels_stopsAtPriceLimit() {
            // 卖盘: @50000 x 1, @50100 x 2, @50200 x 3
            MatchOrder s1 = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            MatchOrder s2 = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("2"));
            MatchOrder s3 = buildLimitOrder(3L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50200"), new BigDecimal("3"));
            orderBook.addOrder(s1);
            orderBook.addOrder(s2);
            orderBook.addOrder(s3);

            // 买单 @50100 x 10，只能吃到50000和50100两个档位(共3)，50200档位不交叉
            MatchOrder buy = buildLimitOrder(10L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("10"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.PARTIALLY_FILLED, buy.getOrderStatus());
            assertEquals(new BigDecimal("7"), buy.getRemainingQuantity());
            assertEquals(OrderStatusEnum.FULL_FILLED, s1.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s2.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, s3.getOrderStatus());
            assertEquals(2, orderBook.getOrderMap().size());
            assertNotNull(orderBook.getOrder(3L));
            assertNotNull(orderBook.getOrder(10L));
        }

        // ---------- 同一档位多个订单（时间优先） ----------

        @Test
        @DisplayName("买单 - 同一价格档位有多个卖单，按时间优先逐个撮合")
        void gtc_buy_multipleOrdersSamePrice_timePriority() {
            // 卖盘 @50000: 先挂1个(qty=2), 再挂1个(qty=3)
            MatchOrder s1 = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("2"));
            MatchOrder s2 = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("3"));
            orderBook.addOrder(s1);
            orderBook.addOrder(s2);

            // 买单 @50000 x 3，应先吃s1(2)，再吃s2(1)
            MatchOrder buy = buildLimitOrder(10L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("3"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s1.getOrderStatus());
            assertEquals(OrderStatusEnum.PARTIALLY_FILLED, s2.getOrderStatus());
            assertEquals(new BigDecimal("2"), s2.getRemainingQuantity());
            assertEquals(1, orderBook.getOrderMap().size());
            assertNotNull(orderBook.getOrder(2L));
        }

        @Test
        @DisplayName("买单 - 同一价格档位多个卖单全部吃完")
        void gtc_buy_multipleOrdersSamePrice_allFilled() {
            MatchOrder s1 = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            MatchOrder s2 = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            MatchOrder s3 = buildLimitOrder(3L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(s1);
            orderBook.addOrder(s2);
            orderBook.addOrder(s3);

            MatchOrder buy = buildLimitOrder(10L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("3"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s1.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s2.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s3.getOrderStatus());
            assertEquals(0, orderBook.getOrderMap().size());
            assertTrue(orderBook.getSellOrders().isEmpty());
        }

        // ---------- 深度档位清理验证 ----------

        @Test
        @DisplayName("撮合后空的深度档位应被清理")
        void gtc_depthLineCleanup_afterFullMatch() {
            // 卖盘: @50000 x 1, @50100 x 1
            orderBook.addOrder(buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1")));
            orderBook.addOrder(buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("1")));
            assertEquals(2, orderBook.getSellOrders().size());

            // 买单 @50100 x 2，吃掉两个档位
            MatchOrder buy = buildLimitOrder(10L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("2"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(0, orderBook.getSellOrders().size());
        }

        @Test
        @DisplayName("撮合后部分消耗的深度档位应保留")
        void gtc_depthLineRetained_afterPartialMatch() {
            // 卖盘 @50000: 两个订单，共 qty=5
            orderBook.addOrder(buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("2")));
            orderBook.addOrder(buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("3")));

            // 买单 @50000 x 1，只吃掉第一个订单的部分
            MatchOrder buy = buildLimitOrder(10L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(1, orderBook.getSellOrders().size());
            assertNotNull(orderBook.getSellOrders().get(new BigDecimal("50000")));
        }

        // ---------- 边界场景 ----------

        @Test
        @DisplayName("买单价格恰好等于最优卖价 - 应交叉并撮合")
        void gtc_buy_priceExactlyAtBestAsk_shouldMatch() {
            orderBook.addOrder(buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1")));

            MatchOrder buy = buildLimitOrder(2L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(0, orderBook.getOrderMap().size());
        }

        @Test
        @DisplayName("卖单价格恰好等于最优买价 - 应交叉并撮合")
        void gtc_sell_priceExactlyAtBestBid_shouldMatch() {
            orderBook.addOrder(buildLimitOrder(1L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1")));

            MatchOrder sell = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));

            matchEngine.processLimitOrder(sell, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, sell.getOrderStatus());
            assertEquals(0, orderBook.getOrderMap().size());
        }

        @Test
        @DisplayName("跨多档位+同档位多订单的复合场景")
        void gtc_buy_complexScenario_multiLevelMultiOrder() {
            // 卖盘构造:
            // @50000: s1(qty=1), s2(qty=2)
            // @50100: s3(qty=3)
            // @50200: s4(qty=4)  <-- 买单价格够不到
            MatchOrder s1 = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            MatchOrder s2 = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("2"));
            MatchOrder s3 = buildLimitOrder(3L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("3"));
            MatchOrder s4 = buildLimitOrder(4L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50200"), new BigDecimal("4"));
            orderBook.addOrder(s1);
            orderBook.addOrder(s2);
            orderBook.addOrder(s3);
            orderBook.addOrder(s4);

            // 买单 @50100 x 5: 先吃s1(1)+s2(2)=3, 再吃s3的2/3, 共5
            MatchOrder buy = buildLimitOrder(10L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("5"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s1.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, s2.getOrderStatus());
            assertEquals(OrderStatusEnum.PARTIALLY_FILLED, s3.getOrderStatus());
            assertEquals(new BigDecimal("1"), s3.getRemainingQuantity());
            assertEquals(OrderStatusEnum.NEW, s4.getOrderStatus());
            assertEquals(2, orderBook.getOrderMap().size());
            assertEquals(2, orderBook.getSellOrders().size());
            assertTrue(orderBook.getBuyOrders().isEmpty());
        }

        @Test
        @DisplayName("小数精度 - 验证BigDecimal精度不丢失")
        void gtc_decimalPrecision_shouldNotLose() {
            orderBook.addOrder(buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("0.00012345"), new BigDecimal("0.001")));

            MatchOrder buy = buildLimitOrder(2L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("0.00012345"), new BigDecimal("0.0007"));

            matchEngine.processLimitOrder(buy, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(new BigDecimal("0.0003"), orderBook.getOrder(1L).getRemainingQuantity());
        }

        @Test
        @DisplayName("卖单 - 跨多个买盘价格档位撮合")
        void gtc_sell_sweepMultipleBidLevels() {
            // 买盘: @50200 x 1, @50100 x 2, @50000 x 1 (买盘按价格降序)
            MatchOrder b1 = buildLimitOrder(1L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50200"), new BigDecimal("1"));
            MatchOrder b2 = buildLimitOrder(2L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("2"));
            MatchOrder b3 = buildLimitOrder(3L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(b1);
            orderBook.addOrder(b2);
            orderBook.addOrder(b3);

            // 卖单 @50000 x 4，从最优买价50200开始吃
            MatchOrder sell = buildLimitOrder(10L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("4"));

            matchEngine.processLimitOrder(sell, orderBook);

            assertEquals(OrderStatusEnum.FULL_FILLED, sell.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, b1.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, b2.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, b3.getOrderStatus());
            assertEquals(0, orderBook.getOrderMap().size());
        }
    }

    // ==================== tryMatchInstantly / match 分支覆盖 ====================

    @Nested
    @DisplayName("撮合核心方法 - 兜底分支覆盖")
    class MatchGuardBranchTest {

        private static final String SYMBOL = "SPOT_BTC_USDT";
        private OrderBook orderBook;

        @BeforeEach
        void initOrderBook() {
            orderBook = new OrderBook(SYMBOL);
        }

        // ---------- placeOrder 重复订单 reject 分支 ----------

        @Test
        @DisplayName("placeOrder - 重复订单应被拒绝")
        void placeOrder_duplicateOrder_shouldReject() {
            matchEngineState.addMatchConfig(createEnabledConfig(SYMBOL));

            // 第一次下单成功
            MatchOrder order1 = buildLimitOrder(1001L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            matchEngine.placeOrder(order1);

            OrderBook book = matchEngineState.getMatchContext().getOrderBook(SYMBOL);
            assertEquals(1, book.getOrderMap().size());
            assertEquals(OrderStatusEnum.NEW, order1.getOrderStatus());

            // 使用相同 orderId 再次下单，应被拒绝
            MatchOrder order2 = buildLimitOrder(1001L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("49000"), new BigDecimal("2"));
            matchEngine.placeOrder(order2);

            // 订单2 应被 reject
            assertEquals(OrderStatusEnum.REJECTED, order2.getOrderStatus());
            // 订单簿中仍只有订单1
            assertEquals(1, book.getOrderMap().size());
            assertNotNull(book.getOrder(1001L));
        }

        // ---------- tryMatchInstantly 空 DepthLine 分支 ----------

        @Test
        @DisplayName("tryMatchInstantly - 遇到空的深度档位应跳过并移除")
        void tryMatchInstantly_emptyDepthLine_shouldSkipAndRemove() {
            // 手动构造一个包含空 DepthLine 的卖盘
            BigDecimal emptyPrice = new BigDecimal("49000");
            BigDecimal normalPrice = new BigDecimal("50000");

            // 先添加一个空的 DepthLine
            DepthLine emptyLine = new DepthLine(emptyPrice);
            orderBook.getSellOrders().put(emptyPrice, emptyLine);

            // 再添加一个正常的卖单
            MatchOrder sell = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, normalPrice, new BigDecimal("1"));
            orderBook.addOrder(sell);

            assertEquals(2, orderBook.getSellOrders().size());
            assertTrue(orderBook.getSellOrders().get(emptyPrice).isEmpty());

            // 买单价格交叉，触发撮合
            MatchOrder buy = buildLimitOrder(2L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));

            matchEngine.tryMatchInstantly(buy, orderBook);

            // 空的档位应被移除
            assertNull(orderBook.getSellOrders().get(emptyPrice));
            // 正常撮合完成
            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, sell.getOrderStatus());
        }

        // ---------- match 对手单为 null 的兜底分支 ----------

        @Test
        @DisplayName("match - 对手单为null时应停止撮合")
        void match_nullOppoOrder_shouldEndMatch() {
            // 创建一个包含 null 的 DepthLine
            BigDecimal price = new BigDecimal("50000");
            DepthLine depthLine = new DepthLine(price);

            // 手动添加 null 到订单列表 (模拟异常情况)
            depthLine.getOrders().add(null);

            // 创建一个新订单
            MatchOrder newOrder = buildLimitOrder(1L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, price, new BigDecimal("1"));

            // 调用 match 方法
            boolean endMatch = matchEngine.match(newOrder, depthLine, orderBook, new ArrayList<>());

            // 应返回 true 表示停止撮合
            assertTrue(endMatch);
            // 新订单状态未变 (未成交)
            assertEquals(OrderStatusEnum.NEW, newOrder.getOrderStatus());
        }

        // ---------- match 对手单为市价单的兜底分支 ----------

        @Test
        @DisplayName("match - 对手单为市价单时应停止撮合")
        void match_marketOppoOrder_shouldEndMatch() {
            BigDecimal price = new BigDecimal("50000");
            DepthLine depthLine = new DepthLine(price);

            // 创建一个市价单作为对手单 (异常情况：市价单不应在订单簿中)
            MatchOrder marketOrder = MatchOrder.builder()
                    .orderId(100L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.MARKET)  // 市价单
                    .orderSide(OrderSideEnum.SELL)
                    .delegateCount(new BigDecimal("1"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();
            depthLine.getOrders().add(marketOrder);

            // 创建一个新订单
            MatchOrder newOrder = buildLimitOrder(1L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, price, new BigDecimal("1"));

            // 调用 match 方法
            boolean endMatch = matchEngine.match(newOrder, depthLine, orderBook, new ArrayList<>());

            // 应返回 true 表示停止撮合
            assertTrue(endMatch);
            // 新订单状态未变 (未成交)
            assertEquals(OrderStatusEnum.NEW, newOrder.getOrderStatus());
        }

        // ---------- tryMatchInstantly 价格不交叉停止撮合分支 ----------

        @Test
        @DisplayName("tryMatchInstantly - 价格不交叉时停止撮合")
        void tryMatchInstantly_priceNotCross_shouldStopMatch() {
            // 卖盘: @50000 x 1, @50100 x 1
            MatchOrder s1 = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            MatchOrder s2 = buildLimitOrder(2L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50100"), new BigDecimal("1"));
            orderBook.addOrder(s1);
            orderBook.addOrder(s2);

            // 买单 @50000 x 0.5，只能吃一部分第一个档位
            MatchOrder buy = buildLimitOrder(10L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("0.5"));

            matchEngine.tryMatchInstantly(buy, orderBook);

            // 买单完全成交
            assertEquals(OrderStatusEnum.FULL_FILLED, buy.getOrderStatus());
            // s1 部分成交
            assertEquals(OrderStatusEnum.PARTIALLY_FILLED, s1.getOrderStatus());
            assertEquals(new BigDecimal("0.5"), s1.getRemainingQuantity());
            // s2 未被触及 (价格不交叉)
            assertEquals(OrderStatusEnum.NEW, s2.getOrderStatus());
        }

        // ---------- clearEmptyDepthLine 分支 ----------

        @Test
        @DisplayName("clearEmptyDepthLine - 非空档位不应被移除")
        void clearEmptyDepthLine_nonEmpty_shouldNotRemove() {
            // 添加一个非空的卖单
            MatchOrder sell = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("10"));
            orderBook.addOrder(sell);

            // 买单数量小于卖单，撮合后档位非空
            MatchOrder buy = buildLimitOrder(2L, SYMBOL, OrderSideEnum.BUY,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("3"));

            matchEngine.tryMatchInstantly(buy, orderBook);

            // 档位应该保留
            assertEquals(1, orderBook.getSellOrders().size());
            assertNotNull(orderBook.getSellOrders().get(new BigDecimal("50000")));
        }
    }

    // ==================== processLimitOrder 100%分支覆盖 ====================

    @Nested
    @DisplayName("processLimitOrder - 100%分支覆盖")
    class ProcessLimitOrderBranchCoverageTest {

        private static final String SYMBOL = "SPOT_BTC_USDT";
        private OrderBook orderBook;

        @BeforeEach
        void initOrderBook() {
            orderBook = new OrderBook(SYMBOL);
        }

        // ========== 分支1: timeInForce == null → return ==========

        @Test
        @DisplayName("分支1: timeInForce为null时直接返回，不处理订单")
        void processLimitOrder_nullTimeInForce_shouldReturnDirectly() {
            MatchOrder order = MatchOrder.builder()
                    .orderId(1001L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.LIMIT)
                    .orderSide(OrderSideEnum.BUY)
                    .timeInForce(null)
                    .delegatePrice(new BigDecimal("50000"))
                    .delegateCount(new BigDecimal("1"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();

            // 不应抛出异常，直接返回
            assertDoesNotThrow(() -> matchEngine.processLimitOrder(order, orderBook));

            // 订单未进入订单簿
            assertNull(orderBook.getOrder(1001L));
            // 订单状态未变
            assertEquals(OrderStatusEnum.NEW, order.getOrderStatus());
        }

        // ========== 分支2: timeInForce == FOK → processFokOrder ==========

        @Test
        @DisplayName("分支2: FOK订单应调用processFokOrder处理")
        void processLimitOrder_FOK_shouldProcessFokOrder() {
            MatchOrder order = MatchOrder.builder()
                    .orderId(1002L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.LIMIT)
                    .orderSide(OrderSideEnum.BUY)
                    .timeInForce(TimeInForceEnum.FOK)
                    .delegatePrice(new BigDecimal("50000"))
                    .delegateCount(new BigDecimal("1"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();

            // FOK 逻辑目前是 TODO，不会抛异常
            assertDoesNotThrow(() -> matchEngine.processLimitOrder(order, orderBook));

            // FOK 当前实现为空，订单不会进入订单簿
            assertNull(orderBook.getOrder(1002L));
        }

        // ========== 分支3: timeInForce == GTC → processGtcOrder ==========

        @Test
        @DisplayName("分支3: GTC订单无交叉时应挂单到订单簿")
        void processLimitOrder_GTC_noCross_shouldAddToBook() {
            MatchOrder order = MatchOrder.builder()
                    .orderId(1003L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.LIMIT)
                    .orderSide(OrderSideEnum.BUY)
                    .timeInForce(TimeInForceEnum.GTC)
                    .delegatePrice(new BigDecimal("50000"))
                    .delegateCount(new BigDecimal("1"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();

            matchEngine.processLimitOrder(order, orderBook);

            // GTC 无对手盘，直接挂单
            assertNotNull(orderBook.getOrder(1003L));
            assertEquals(OrderStatusEnum.NEW, order.getOrderStatus());
        }

        @Test
        @DisplayName("分支3: GTC订单有交叉时应撮合")
        void processLimitOrder_GTC_withCross_shouldMatch() {
            // 先挂一个卖单
            MatchOrder sellOrder = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(sellOrder);

            // GTC 买单价格交叉
            MatchOrder buyOrder = MatchOrder.builder()
                    .orderId(1003L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.LIMIT)
                    .orderSide(OrderSideEnum.BUY)
                    .timeInForce(TimeInForceEnum.GTC)
                    .delegatePrice(new BigDecimal("50000"))
                    .delegateCount(new BigDecimal("1"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();

            matchEngine.processLimitOrder(buyOrder, orderBook);

            // 双方完全成交
            assertEquals(OrderStatusEnum.FULL_FILLED, buyOrder.getOrderStatus());
            assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder.getOrderStatus());
        }

        // ========== 分支4: timeInForce == POST_ONLY → processPostOnlyOrder ==========

        @Test
        @DisplayName("分支4: POST_ONLY订单无交叉时应挂单")
        void processLimitOrder_POST_ONLY_noCross_shouldAddToBook() {
            MatchOrder order = MatchOrder.builder()
                    .orderId(1004L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.LIMIT)
                    .orderSide(OrderSideEnum.BUY)
                    .timeInForce(TimeInForceEnum.POST_ONLY)
                    .delegatePrice(new BigDecimal("50000"))
                    .delegateCount(new BigDecimal("1"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();

            matchEngine.processLimitOrder(order, orderBook);

            // POST_ONLY 无对手盘，挂单成功
            assertNotNull(orderBook.getOrder(1004L));
            assertEquals(OrderStatusEnum.NEW, order.getOrderStatus());
        }

        @Test
        @DisplayName("分支4: POST_ONLY订单有交叉时应撤单")
        void processLimitOrder_POST_ONLY_withCross_shouldCancel() {
            // 先挂一个卖单
            MatchOrder sellOrder = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("49000"), new BigDecimal("1"));
            orderBook.addOrder(sellOrder);

            // POST_ONLY 买单价格交叉
            MatchOrder buyOrder = MatchOrder.builder()
                    .orderId(1004L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.LIMIT)
                    .orderSide(OrderSideEnum.BUY)
                    .timeInForce(TimeInForceEnum.POST_ONLY)
                    .delegatePrice(new BigDecimal("50000"))
                    .delegateCount(new BigDecimal("1"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();

            matchEngine.processLimitOrder(buyOrder, orderBook);

            // POST_ONLY 交叉时撤单
            assertEquals(OrderStatusEnum.CANCELLED, buyOrder.getOrderStatus());
            assertNull(orderBook.getOrder(1004L));
        }

        // ========== 分支5: timeInForce == IOC → processIocOrder ==========

        @Test
        @DisplayName("分支5: IOC订单无交叉时应直接撤单")
        void processLimitOrder_IOC_noCross_shouldCancel() {
            // 先挂一个卖单，价格高于买单
            MatchOrder sellOrder = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("51000"), new BigDecimal("1"));
            orderBook.addOrder(sellOrder);

            // IOC 买单价格不交叉
            MatchOrder buyOrder = MatchOrder.builder()
                    .orderId(1005L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.LIMIT)
                    .orderSide(OrderSideEnum.BUY)
                    .timeInForce(TimeInForceEnum.IOC)
                    .delegatePrice(new BigDecimal("50000"))
                    .delegateCount(new BigDecimal("1"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();

            matchEngine.processLimitOrder(buyOrder, orderBook);

            // IOC 无交叉直接撤单
            assertEquals(OrderStatusEnum.CANCELLED, buyOrder.getOrderStatus());
            assertNull(orderBook.getOrder(1005L));
        }

        @Test
        @DisplayName("分支5: IOC订单有交叉完全成交")
        void processLimitOrder_IOC_withCross_fullFilled() {
            // 先挂一个卖单
            MatchOrder sellOrder = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(sellOrder);

            // IOC 买单价格交叉，数量相等
            MatchOrder buyOrder = MatchOrder.builder()
                    .orderId(1005L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.LIMIT)
                    .orderSide(OrderSideEnum.BUY)
                    .timeInForce(TimeInForceEnum.IOC)
                    .delegatePrice(new BigDecimal("50000"))
                    .delegateCount(new BigDecimal("1"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();

            matchEngine.processLimitOrder(buyOrder, orderBook);

            // IOC 完全成交
            assertEquals(OrderStatusEnum.FULL_FILLED, buyOrder.getOrderStatus());
        }

        @Test
        @DisplayName("分支5: IOC订单有交叉部分成交后撤单")
        void processLimitOrder_IOC_withCross_partialFilled_thenCancel() {
            // 先挂一个卖单，数量小于买单
            MatchOrder sellOrder = buildLimitOrder(1L, SYMBOL, OrderSideEnum.SELL,
                    TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
            orderBook.addOrder(sellOrder);

            // IOC 买单数量大于卖单
            MatchOrder buyOrder = MatchOrder.builder()
                    .orderId(1005L)
                    .symbolId(SYMBOL)
                    .orderType(OrderType.LIMIT)
                    .orderSide(OrderSideEnum.BUY)
                    .timeInForce(TimeInForceEnum.IOC)
                    .delegatePrice(new BigDecimal("50000"))
                    .delegateCount(new BigDecimal("2"))
                    .dealtCount(BigDecimal.ZERO)
                    .orderStatus(OrderStatusEnum.NEW)
                    .build();

            matchEngine.processLimitOrder(buyOrder, orderBook);

            // IOC 部分成交后撤单
            assertEquals(OrderStatusEnum.CANCELLED, buyOrder.getOrderStatus());
            assertEquals(new BigDecimal("1"), buyOrder.getDealtCount());
        }
    }

    @Test
    @DisplayName("第一个市价单直接撮合")
    void testMarketMatchInstantly() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 下市价单
        MatchOrder buyMarket = buildMarketOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, null, new BigDecimal("0.5"));
        matchEngine.placeOrder(buyMarket);

        Assertions.assertEquals(OrderStatusEnum.FULL_FILLED, buyMarket.getOrderStatus());
        matchEngine.getMatchEngineState().getMatchContext().getOrderBook(symbol).printOrderBook(SCENE);
    }

    @Test
    @DisplayName("第二个市价单不撮合")
    void testMarketMatchNotInstantly() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        matchEngine.placeOrder(sellOrder);

        // 下市价单
        MatchOrder buyMarket = buildMarketOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, null, new BigDecimal("1.5"));
        matchEngine.placeOrder(buyMarket);

        MatchOrder buyMarket1 = buildMarketOrder(1003L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, null, new BigDecimal("0.5"));
        matchEngine.placeOrder(buyMarket1);

        Assertions.assertEquals(OrderStatusEnum.PARTIALLY_FILLED, buyMarket.getOrderStatus());
        Assertions.assertEquals(OrderStatusEnum.NEW, buyMarket1.getOrderStatus());

        matchEngine.getMatchEngineState().getMatchContext().getOrderBook(symbol).printOrderBook(SCENE);
    }

    @Test
    @DisplayName("不配置自成交保护,正常撮合")
    void testSTP_normal() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        setStpParams(sellOrder, StpStrategyEnum.CANCEL_BOTH, sellOrder.getAccountId());
        matchEngine.placeOrder(sellOrder);

        // 下市价单
        MatchOrder buyMarket = buildMarketOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, null, new BigDecimal("1.5"));
        setStpParams(buyMarket, null, -1L);
        matchEngine.placeOrder(buyMarket);

        Assertions.assertEquals(OrderStatusEnum.FULL_FILLED, sellOrder.getOrderStatus());
        Assertions.assertEquals(OrderStatusEnum.PARTIALLY_FILLED, buyMarket.getOrderStatus());
        matchEngine.getMatchEngineState().getMatchContext().getOrderBook(symbol).printOrderBook(SCENE);
    }

    @Test
    @DisplayName("配置自成交保护,撤销当前")
    void testSTP_cancelTaker() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        setStpParams(sellOrder, StpStrategyEnum.DEFAULT, sellOrder.getAccountId());
        matchEngine.placeOrder(sellOrder);

        // 下市价单
        MatchOrder buyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("50000.1"), new BigDecimal("1.5"));
        setStpParams(buyOrder, StpStrategyEnum.CANCEL_TAKER, buyOrder.getAccountId());
        matchEngine.placeOrder(buyOrder);

        Assertions.assertEquals(OrderStatusEnum.NEW, sellOrder.getOrderStatus());
        Assertions.assertEquals(OrderStatusEnum.CANCELLED, buyOrder.getOrderStatus());
    }

    @Test
    @DisplayName("配置自成交保护,撤销挂单")
    void testSTP_cancelMaker() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        setStpParams(sellOrder, StpStrategyEnum.CANCEL_BOTH, sellOrder.getAccountId());
        matchEngine.placeOrder(sellOrder);

        // 下市价单
        MatchOrder buyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("50000.1"), new BigDecimal("1.5"));
        setStpParams(buyOrder, StpStrategyEnum.CANCEL_MAKER, buyOrder.getAccountId());
        matchEngine.placeOrder(buyOrder);

        Assertions.assertEquals(OrderStatusEnum.CANCELLED, sellOrder.getOrderStatus());
        Assertions.assertEquals(OrderStatusEnum.NEW, buyOrder.getOrderStatus());
        Assertions.assertEquals(1, matchEngine.getMatchEngineState().getMatchContext().getOrderBook(symbol).getOrderMap().size());
    }

    @Test
    @DisplayName("配置自成交保护,撤销双向")
    void testSTP_cancelBoth() {
        String symbol = "SPOT_BTC_USDT";

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbol);

        // 添加币对配置 确保开启币对交易
        MatchEngine matchEngine = new MatchEngine();
        MatchEngineState engineState = matchEngine.getMatchEngineState();
        engineState.addMatchConfig(matchConfig);

        // 下订单1
        MatchOrder sellOrder = buildLimitOrder(1001L, symbol, OrderSideEnum.SELL,
                TimeInForceEnum.GTC, new BigDecimal("50000"), new BigDecimal("1"));
        setStpParams(sellOrder, StpStrategyEnum.CANCEL_MAKER, sellOrder.getAccountId());
        matchEngine.placeOrder(sellOrder);

        // 下市价单
        MatchOrder buyOrder = buildLimitOrder(1002L, symbol, OrderSideEnum.BUY,
                TimeInForceEnum.GTC, new BigDecimal("50000.1"), new BigDecimal("1.5"));
        setStpParams(buyOrder, StpStrategyEnum.CANCEL_BOTH, buyOrder.getAccountId());
        matchEngine.placeOrder(buyOrder);

        Assertions.assertEquals(OrderStatusEnum.CANCELLED, sellOrder.getOrderStatus());
        Assertions.assertEquals(OrderStatusEnum.CANCELLED, buyOrder.getOrderStatus());
        Assertions.assertEquals(0, matchEngine.getMatchEngineState().getMatchContext().getOrderBook(symbol).getOrderMap().size());
    }


    private MatchOrder buildMarketOrder(long orderId, String symbol, OrderSideEnum side,
                                       TimeInForceEnum tif, BigDecimal price, BigDecimal qty) {
        return MatchOrder.builder()
                .orderId(orderId)
                .symbolId(symbol)
                .orderType(OrderType.MARKET)
                .orderSide(side)
                .timeInForce(tif)
                .delegatePrice(price)
                .delegateCount(qty)
                .orderStatus(OrderStatusEnum.NEW)
                .build();
    }

    private void setStpParams(MatchOrder matchOrder, StpStrategyEnum stpStrategyEnum, long stpAccountId) {
        matchOrder.setStpAccountId(stpAccountId);
        matchOrder.setStpStrategyEnum(stpStrategyEnum);
    }

}