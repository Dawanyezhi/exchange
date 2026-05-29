package com.laser.exchange.matching.core.engine;

import com.laser.exchange.common.config.SymbolConfig;
import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.common.result.PlaceOrderResult;
import com.laser.exchange.matching.core.model.MatchConfig;
import com.laser.exchange.matching.core.model.MatchEngineState;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户要求专项测试：
 * <p><b>不变量</b>: 任何 placeOrder 调用，事件序列的第 0 个必须是 {@link PlaceOrderResult}（PLACE_ORDER bizType）。
 *
 * <p>覆盖场景：
 * <ol>
 *   <li>挂单（GTC/POST_ONLY/IOC, 无对手 / 不交叉）</li>
 *   <li>挂单，部分成交（GTC 部分吃 maker，剩余挂单）</li>
 *   <li>挂单，部分成交，部分成交（GTC 跨多档部分吃，剩余挂单）</li>
 *   <li>挂单，撤单（POST_ONLY 交叉被撤、IOC 不交叉被撤）</li>
 *   <li>挂单，部分成交，撤单（IOC 部分成交后撤剩余）</li>
 *   <li>挂单，完全成交（GTC 一笔吃完）</li>
 *   <li>挂单，多次成交，完全成交（GTC 跨多个 maker 吃完）</li>
 * </ol>
 */
class PlaceOrderEventFirstInvariantTest {

    private MatchEngine engine;
    private MatchEngineState state;
    private MatchResultEventsHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        engine = new MatchEngine();
        helper = new MatchResultEventsHelper();
        Field f = MatchEngine.class.getDeclaredField("eventsHelper");
        f.setAccessible(true);
        f.set(engine, helper);

        Field stateField = MatchEngine.class.getDeclaredField("matchEngineState");
        stateField.setAccessible(true);
        state = (MatchEngineState) stateField.get(engine);

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

    /**
     * 抓手方法：执行一次 placeOrder 并断言 events[0] 是 PLACE_ORDER。
     * 返回事件列表供后续场景断言。
     */
    private List<MatchResult> placeAndAssertPlaceFirst(MatchOrder order, long requestSerialNum, String scenario) {
        helper.beginRequest(requestSerialNum, requestSerialNum * 100L);
        engine.placeOrder(order);
        List<MatchResult> events = helper.endRequest();

        assertFalse(events.isEmpty(), "[" + scenario + "] events 不应为空");
        assertEquals(ResultBizTypeEnum.PLACE_ORDER, events.get(0).getResultBizType(),
                "[" + scenario + "] events[0] 必须是 PLACE_ORDER");
        assertTrue(events.get(0) instanceof PlaceOrderResult,
                "[" + scenario + "] events[0] 必须是 PlaceOrderResult 类型");
        return events;
    }

    private void seedMaker(long id, OrderSideEnum side, BigDecimal price, BigDecimal qty, long reqSeq) {
        helper.beginRequest(reqSeq, reqSeq * 100L);
        engine.placeOrder(limit(id, side, price, qty, TimeInForceEnum.GTC));
        helper.endRequest();
    }

    // ============ 场景 1: 纯挂单 ============

    @Test
    @DisplayName("场景1a: GTC 无对手 → events=[PLACE]")
    void scenario1a_gtcNoCounterparty() {
        var events = placeAndAssertPlaceFirst(
                limit(1, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC),
                1L, "GTC 无对手");
        assertEquals(1, events.size());
        assertEquals(ResultBizTypeEnum.PLACE_ORDER, events.get(0).getResultBizType());
    }

    @Test
    @DisplayName("场景1b: GTC 不交叉 → events=[PLACE]")
    void scenario1b_gtcNotCross() {
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("110"), BigDecimal.ONE, 1L);
        var events = placeAndAssertPlaceFirst(
                limit(2, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC),
                2L, "GTC 不交叉");
        assertEquals(1, events.size());
    }

    @Test
    @DisplayName("场景1c: POST_ONLY 不交叉 → events=[PLACE]")
    void scenario1c_postOnlyNotCross() {
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("110"), BigDecimal.ONE, 1L);
        var events = placeAndAssertPlaceFirst(
                limit(2, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.POST_ONLY),
                2L, "POST_ONLY 不交叉");
        assertEquals(1, events.size());
    }

    // ============ 场景 2: 部分成交 ============

    @Test
    @DisplayName("场景2: 部分成交后挂剩余 → events=[PLACE, MATCH]")
    void scenario2_partialFillThenRest() {
        // sell 1@100
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, 1L);

        // buy 3@100 → 吃 1，剩 2 挂
        var events = placeAndAssertPlaceFirst(
                limit(2, OrderSideEnum.BUY, new BigDecimal("100"), new BigDecimal("3"), TimeInForceEnum.GTC),
                2L, "部分成交挂剩余");

        assertEquals(2, events.size());
        assertEquals(ResultBizTypeEnum.MATCH, events.get(1).getResultBizType());
    }

    // ============ 场景 3: 多档部分成交 ============

    @Test
    @DisplayName("场景3: 跨多档部分成交挂剩余 → events=[PLACE, MATCH, MATCH]")
    void scenario3_multiLevelPartial() {
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, 1L);
        seedMaker(2, OrderSideEnum.SELL, new BigDecimal("101"), BigDecimal.ONE, 2L);

        var events = placeAndAssertPlaceFirst(
                limit(10, OrderSideEnum.BUY, new BigDecimal("101"), new BigDecimal("5"), TimeInForceEnum.GTC),
                3L, "跨档部分成交");

        assertTrue(events.size() >= 3);
        // events[0]=PLACE, events[1..]=MATCH
        for (int i = 1; i < events.size(); i++) {
            assertEquals(ResultBizTypeEnum.MATCH, events.get(i).getResultBizType());
        }
    }

    // ============ 场景 4: 挂单后被撤 ============

    @Test
    @DisplayName("场景4a: POST_ONLY 交叉立即撤 → events=[PLACE, CANCEL]")
    void scenario4a_postOnlyCross() {
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, 1L);
        var events = placeAndAssertPlaceFirst(
                limit(2, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.POST_ONLY),
                2L, "POST_ONLY 交叉撤");
        assertEquals(2, events.size());
        assertEquals(ResultBizTypeEnum.CANCEL, events.get(1).getResultBizType());
    }

    @Test
    @DisplayName("场景4b: IOC 不交叉立即撤 → events=[PLACE, CANCEL]")
    void scenario4b_iocNotCross() {
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("110"), BigDecimal.ONE, 1L);
        var events = placeAndAssertPlaceFirst(
                limit(2, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.IOC),
                2L, "IOC 不交叉撤");
        assertEquals(2, events.size());
        assertEquals(ResultBizTypeEnum.CANCEL, events.get(1).getResultBizType());
    }

    // ============ 场景 5: 部分成交后撤剩余 ============

    @Test
    @DisplayName("场景5: IOC 部分成交后撤剩余 → events=[PLACE, MATCH, CANCEL]")
    void scenario5_iocPartialFillCancel() {
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, 1L);

        var events = placeAndAssertPlaceFirst(
                limit(2, OrderSideEnum.BUY, new BigDecimal("100"), new BigDecimal("3"), TimeInForceEnum.IOC),
                2L, "IOC 部分成交撤剩");

        assertEquals(3, events.size());
        assertEquals(ResultBizTypeEnum.MATCH, events.get(1).getResultBizType());
        assertEquals(ResultBizTypeEnum.CANCEL, events.get(2).getResultBizType());
    }

    // ============ 场景 6: 完全成交 ============

    @Test
    @DisplayName("场景6: GTC 一笔完全成交 → events=[PLACE, MATCH]")
    void scenario6_gtcFullFill() {
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("100"), new BigDecimal("5"), 1L);

        var events = placeAndAssertPlaceFirst(
                limit(2, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC),
                2L, "GTC 完全成交");

        assertEquals(2, events.size());
        assertEquals(ResultBizTypeEnum.MATCH, events.get(1).getResultBizType());
    }

    // ============ 场景 7: 多次成交直至完全成交 ============

    @Test
    @DisplayName("场景7: GTC 跨多个 maker 完全成交 → events=[PLACE, MATCH×N]")
    void scenario7_gtcMultiMakersFullFill() {
        // 3 个 maker, 各 1 个
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, 1L);
        seedMaker(2, OrderSideEnum.SELL, new BigDecimal("101"), BigDecimal.ONE, 2L);
        seedMaker(3, OrderSideEnum.SELL, new BigDecimal("102"), BigDecimal.ONE, 3L);

        // taker buy 3@102 → 完全成交，吃 3 个 maker
        var events = placeAndAssertPlaceFirst(
                limit(10, OrderSideEnum.BUY, new BigDecimal("102"), new BigDecimal("3"), TimeInForceEnum.GTC),
                4L, "跨多 maker 完全成交");

        assertEquals(4, events.size());  // 1 PLACE + 3 MATCH
        for (int i = 1; i < events.size(); i++) {
            assertEquals(ResultBizTypeEnum.MATCH, events.get(i).getResultBizType());
        }
    }

    // ============ 场景 8: 同档位多 maker FIFO 成交 ============

    @Test
    @DisplayName("场景8: 同档位 FIFO 完全成交 → events=[PLACE, MATCH×N]")
    void scenario8_sameDepthFifoFullFill() {
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, 1L);
        seedMaker(2, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, 2L);

        var events = placeAndAssertPlaceFirst(
                limit(10, OrderSideEnum.BUY, new BigDecimal("100"), new BigDecimal("2"), TimeInForceEnum.GTC),
                3L, "同档 FIFO 完全成交");

        assertEquals(3, events.size());  // PLACE + 2 MATCH
    }

    // ============ 场景 9: PLACE 永远不被遗漏（即便后续无任何 match/cancel） ============

    @Test
    @DisplayName("场景9: 极简纯挂单 → 一定有且仅有 PLACE")
    void scenario9_pureBareBonesPlace() {
        var events = placeAndAssertPlaceFirst(
                limit(1, OrderSideEnum.SELL, new BigDecimal("99999"), BigDecimal.ONE, TimeInForceEnum.GTC),
                1L, "纯挂单");
        assertEquals(1, events.size());
    }

    // ============ 场景 10: 不变量元测试 — 按用户描述穷举 ============

    @Test
    @DisplayName("场景10: 不变量验证 — 所有 placeOrder 路径 events[0] 都是 PLACE_ORDER")
    void invariant_placeAlwaysFirst_anyTif() {
        // 准备多种对手盘
        seedMaker(1, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, 1L);
        seedMaker(2, OrderSideEnum.SELL, new BigDecimal("101"), BigDecimal.ONE, 2L);
        seedMaker(3, OrderSideEnum.BUY, new BigDecimal("90"), BigDecimal.ONE, 3L);

        long reqSeq = 10;
        // 遍历各种 TIF + 各种 side + 各种 price
        TimeInForceEnum[] tifs = {TimeInForceEnum.GTC, TimeInForceEnum.IOC, TimeInForceEnum.POST_ONLY};
        OrderSideEnum[] sides = {OrderSideEnum.BUY, OrderSideEnum.SELL};
        BigDecimal[] prices = {new BigDecimal("99"), new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("102")};

        for (TimeInForceEnum tif : tifs) {
            for (OrderSideEnum side : sides) {
                for (BigDecimal price : prices) {
                    long id = ++reqSeq;
                    var events = placeAndAssertPlaceFirst(
                            limit(id, side, price, BigDecimal.ONE, tif),
                            id, "tif=" + tif + " side=" + side + " price=" + price);
                    // 不变量已断言；这里额外 assert events 非空
                    assertFalse(events.isEmpty());
                }
            }
        }
    }
}
