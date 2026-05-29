package com.laser.exchange.matching.core.engine;

import com.laser.exchange.common.config.SymbolConfig;
import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.common.result.CancelOrderResult;
import com.laser.exchange.matching.core.model.MatchConfig;
import com.laser.exchange.matching.core.model.MatchEngineState;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.matching.core.model.OrderBook;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证撮合事件生成顺序：
 * <p><b>用户要求</b>: 挂单 → 部分/完全成交*N → 撤单(可选) 严格顺序
 */
class MatchEventOrderTest {

    private MatchEngine engine;
    private MatchEngineState state;
    private MatchResultEventsHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        engine = new MatchEngine();
        helper = new MatchResultEventsHelper();
        // 注入 helper 取代 fallback 实例
        Field f = MatchEngine.class.getDeclaredField("eventsHelper");
        f.setAccessible(true);
        f.set(engine, helper);

        Field stateField = MatchEngine.class.getDeclaredField("matchEngineState");
        stateField.setAccessible(true);
        state = (MatchEngineState) stateField.get(engine);

        // 配置 symbol
        SymbolConfig cfg = new SymbolConfig();
        cfg.setSymbolId(1);
        cfg.setSymbolName("BTC_USDT");
        state.getMatchContext().addSymbol(1, cfg);
        MatchConfig mc = new MatchConfig();
        mc.setSymbol("BTC_USDT");
        mc.setEnabled(true);
        state.addMatchConfig(mc);
    }

    private MatchOrder limit(long id, OrderSideEnum side, BigDecimal price, BigDecimal qty, TimeInForceEnum tif) {
        return MatchOrder.builder()
                .orderId(id)
                .symbolId("BTC_USDT")
                .accountId(1000L + id)
                .orderType(OrderType.LIMIT)
                .orderSide(side)
                .timeInForce(tif)
                .delegatePrice(price)
                .delegateCount(qty)
                .dealtCount(BigDecimal.ZERO)
                .orderStatus(OrderStatusEnum.NEW)
                .stpStrategyEnum(StpStrategyEnum.DEFAULT)
                .stpAccountId(1000L + id)
                .createTime(id)
                .updateTime(id)
                .build();
    }

    private MatchOrder market(long id, OrderSideEnum side, BigDecimal qty, BigDecimal lockedQuoteAmount) {
        return MatchOrder.builder()
                .orderId(id)
                .symbolId("BTC_USDT")
                .accountId(1000L + id)
                .orderType(OrderType.MARKET)
                .orderSide(side)
                .timeInForce(TimeInForceEnum.GTC)
                .delegatePrice(BigDecimal.ZERO)
                .delegateCount(qty)
                .lockedQuoteAmount(lockedQuoteAmount)
                .usedQuoteAmount(BigDecimal.ZERO)
                .dealtCount(BigDecimal.ZERO)
                .orderStatus(OrderStatusEnum.NEW)
                .stpStrategyEnum(StpStrategyEnum.DEFAULT)
                .stpAccountId(1000L + id)
                .createTime(id)
                .updateTime(id)
                .build();
    }

    @Test
    @DisplayName("纯挂单(GTC,无对手) → 仅 1 个 PLACE 事件")
    void placeOnly_emitsPlaceOnly() {
        helper.beginRequest(1L, 100L);
        engine.placeOrder(limit(1, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
        List<MatchResult> events = helper.endRequest();

        assertEquals(1, events.size());
        assertEquals(ResultBizTypeEnum.PLACE_ORDER, events.get(0).getResultBizType());
    }

    @Test
    @DisplayName("挂单 + 1 笔成交 → 顺序: PLACE → MATCH")
    void placeThenMatch_emitsPlaceFirst() {
        // 先挂一个 sell maker
        helper.beginRequest(1L, 100L);
        engine.placeOrder(limit(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
        helper.endRequest();

        // 来一个 buy taker，能完全吃掉 maker
        helper.beginRequest(2L, 200L);
        engine.placeOrder(limit(2, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
        List<MatchResult> events = helper.endRequest();

        assertEquals(2, events.size(), "should be exactly PLACE + MATCH");
        assertEquals(ResultBizTypeEnum.PLACE_ORDER, events.get(0).getResultBizType(),
                "事件 0 必须是 PLACE_ORDER");
        assertEquals(ResultBizTypeEnum.MATCH, events.get(1).getResultBizType(),
                "事件 1 必须是 MATCH");
    }

    @Test
    @DisplayName("挂单 + 多笔成交 → 顺序: PLACE → MATCH × N")
    void placeThenMultipleMatches_emitsPlaceThenMatches() {
        // 挂 3 个 sell maker
        for (int i = 1; i <= 3; i++) {
            helper.beginRequest(i, i * 10L);
            engine.placeOrder(limit(i, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
            helper.endRequest();
        }

        // 大 buy taker：能吃 3 个 maker
        helper.beginRequest(10L, 1000L);
        engine.placeOrder(limit(10, OrderSideEnum.BUY, new BigDecimal("100"), new BigDecimal("3"), TimeInForceEnum.GTC));
        List<MatchResult> events = helper.endRequest();

        assertEquals(4, events.size(), "PLACE + 3 MATCH = 4 events");
        assertEquals(ResultBizTypeEnum.PLACE_ORDER, events.get(0).getResultBizType());
        for (int i = 1; i <= 3; i++) {
            assertEquals(ResultBizTypeEnum.MATCH, events.get(i).getResultBizType(),
                    "事件 " + i + " 必须是 MATCH");
        }
    }

    @Test
    @DisplayName("POST_ONLY 交叉 → 顺序: PLACE → CANCEL")
    void postOnlyCross_emitsPlaceThenCancel() {
        // 先挂 sell
        helper.beginRequest(1L, 100L);
        engine.placeOrder(limit(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
        helper.endRequest();

        // POST_ONLY buy at 100 → 与 sell 交叉，应被撤
        helper.beginRequest(2L, 200L);
        engine.placeOrder(limit(2, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.POST_ONLY));
        List<MatchResult> events = helper.endRequest();

        assertEquals(2, events.size());
        assertEquals(ResultBizTypeEnum.PLACE_ORDER, events.get(0).getResultBizType());
        assertEquals(ResultBizTypeEnum.CANCEL, events.get(1).getResultBizType());
    }

    @Test
    @DisplayName("IOC 不交叉 → 顺序: PLACE → CANCEL(IOC_NOT_CROSS)")
    void iocNotCross_emitsPlaceThenCancel() {
        // sell 在 110
        helper.beginRequest(1L, 100L);
        engine.placeOrder(limit(1, OrderSideEnum.SELL, new BigDecimal("110"), BigDecimal.ONE, TimeInForceEnum.GTC));
        helper.endRequest();

        // IOC buy at 100 → 不交叉
        helper.beginRequest(2L, 200L);
        engine.placeOrder(limit(2, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.IOC));
        List<MatchResult> events = helper.endRequest();

        assertEquals(2, events.size());
        assertEquals(ResultBizTypeEnum.PLACE_ORDER, events.get(0).getResultBizType());
        assertEquals(ResultBizTypeEnum.CANCEL, events.get(1).getResultBizType());
    }

    @Test
    @DisplayName("IOC 部分成交 → 顺序: PLACE → MATCH → CANCEL(IOC_NOT_FULLFILL)")
    void iocPartialFill_emitsPlaceMatchCancel() {
        // 仅 1 个 sell qty=1
        helper.beginRequest(1L, 100L);
        engine.placeOrder(limit(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
        helper.endRequest();

        // IOC buy qty=2 (部分成交，剩余撤)
        helper.beginRequest(2L, 200L);
        engine.placeOrder(limit(2, OrderSideEnum.BUY, new BigDecimal("100"), new BigDecimal("2"), TimeInForceEnum.IOC));
        List<MatchResult> events = helper.endRequest();

        assertEquals(3, events.size());
        assertEquals(ResultBizTypeEnum.PLACE_ORDER, events.get(0).getResultBizType());
        assertEquals(ResultBizTypeEnum.MATCH, events.get(1).getResultBizType());
        assertEquals(ResultBizTypeEnum.CANCEL, events.get(2).getResultBizType());
    }

    @Test
    @DisplayName("市价单部分成交 → 顺序: PLACE → MATCH → CANCEL(MARKET_NOT_FULLFILL)")
    void marketPartialFill_emitsPlaceMatchCancel() {
        helper.beginRequest(1L, 100L);
        engine.placeOrder(limit(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
        helper.endRequest();

        helper.beginRequest(2L, 200L);
        engine.placeOrder(market(2, OrderSideEnum.BUY, new BigDecimal("3"), new BigDecimal("1000")));
        List<MatchResult> events = helper.endRequest();

        assertEquals(3, events.size());
        assertEquals(ResultBizTypeEnum.PLACE_ORDER, events.get(0).getResultBizType());
        assertEquals(ResultBizTypeEnum.MATCH, events.get(1).getResultBizType());
        assertEquals(ResultBizTypeEnum.CANCEL, events.get(2).getResultBizType());
        assertEquals(CancelReasonEnum.MARKET_NOT_FULLFILL_CANCEL_REMAINING,
                ((CancelOrderResult) events.get(2)).getCancelReason());
    }

    @Test
    @DisplayName("用户主动撤单 → 仅 1 个 CANCEL 事件")
    void userCancel_emitsCancel() {
        // 先挂一单
        helper.beginRequest(1L, 100L);
        engine.placeOrder(limit(1, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
        helper.endRequest();

        // 用户撤单
        helper.beginRequest(2L, 200L);
        engine.cancelOrder(1L, "BTC_USDT");
        List<MatchResult> events = helper.endRequest();

        assertEquals(1, events.size());
        assertEquals(ResultBizTypeEnum.CANCEL, events.get(0).getResultBizType());
    }

    @Test
    @DisplayName("resultSerialNum 在事件序列内严格 +1 单调")
    void resultSerialNumStrictlyMonotonic() {
        // 挂 maker
        helper.beginRequest(1L, 100L);
        engine.placeOrder(limit(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
        helper.endRequest();

        // 大 buy 吃多笔
        helper.beginRequest(2L, 200L);
        engine.placeOrder(limit(2, OrderSideEnum.SELL, new BigDecimal("101"), BigDecimal.ONE, TimeInForceEnum.GTC));
        helper.endRequest();

        helper.beginRequest(3L, 300L);
        engine.placeOrder(limit(10, OrderSideEnum.BUY, new BigDecimal("102"), new BigDecimal("2"), TimeInForceEnum.GTC));
        List<MatchResult> events = helper.endRequest();

        long prev = events.get(0).getResultSerialNum() - 1;
        for (MatchResult e : events) {
            assertEquals(prev + 1, e.getResultSerialNum(),
                    "resultSerialNum 必须严格 +1 单调（跨 batch 全局）");
            prev = e.getResultSerialNum();
        }
    }
}
