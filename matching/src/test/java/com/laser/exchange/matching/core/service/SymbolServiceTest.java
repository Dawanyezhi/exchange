package com.laser.exchange.matching.core.service;

import com.laser.exchange.common.config.SymbolConfig;
import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.SymbolOpEnum;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.common.result.TradeSwitchResult;
import com.laser.exchange.common.result.UpDownSymbolResult;
import com.laser.exchange.matching.core.engine.MatchEngine;
import com.laser.exchange.matching.core.model.MatchConfig;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SymbolService 单元测试 — 上下币 / 开关交易完整生命周期。
 *
 * <p>使用真实 MatchEngine + EventsHelper，不依赖 cluster。
 */
class SymbolServiceTest {

    private MatchEngine engine;
    private MatchResultEventsHelper helper;
    private SymbolService symbolService;

    @BeforeEach
    void setUp() throws Exception {
        engine = new MatchEngine();
        helper = new MatchResultEventsHelper();
        // 注入 helper 到 engine 替换 fallback 实例
        Field f = MatchEngine.class.getDeclaredField("eventsHelper");
        f.setAccessible(true);
        f.set(engine, helper);

        symbolService = new SymbolService();
        Field me = SymbolService.class.getDeclaredField("matchEngine");
        me.setAccessible(true);
        me.set(symbolService, engine);
        Field eh = SymbolService.class.getDeclaredField("eventsHelper");
        eh.setAccessible(true);
        eh.set(symbolService, helper);
    }

    @Test
    @DisplayName("上币: 注册 SymbolConfig + 默认 disabled MatchConfig + 创建空 OrderBook")
    void list_registersSymbolWithDefaultDisabled() {
        helper.beginRequest(0L, 1700000000000L);
        symbolService.listSymbol(3, "doge-usdt", 100, 200);
        List<MatchResult> events = helper.endRequest();

        // 1) SymbolConfig 已注册
        SymbolConfig cfg = engine.getMatchEngineState().getMatchContext()
                .getSymbolConfigMap().get(3);
        assertNotNull(cfg);
        assertEquals("doge-usdt", cfg.getSymbolName());
        assertEquals(100, cfg.getBaseCoinId());
        assertEquals(200, cfg.getQuoteCoinId());

        // 2) MatchConfig 默认 disabled
        MatchConfig mc = engine.getMatchEngineState().getMatchConfig("doge-usdt");
        assertNotNull(mc);
        assertFalse(mc.isEnabled(), "上币默认 enabled=false");

        // 3) OrderBook 已预创建
        assertNotNull(engine.getMatchEngineState().getMatchContext().getOrderBook("doge-usdt"));

        // 4) 事件: 1 个 SYMBOL_UP
        assertEquals(1, events.size());
        UpDownSymbolResult evt = (UpDownSymbolResult) events.get(0);
        assertEquals(SymbolOpEnum.LIST, evt.getOp());
        assertEquals(ResultBizTypeEnum.SYMBOL_UP, evt.getResultBizType());
        assertEquals(3, evt.getSymbolCode());
        assertEquals("doge-usdt", evt.getSymbolName());
    }

    @Test
    @DisplayName("下币: 移除 SymbolConfig + MatchConfig.enabled=false + 移除 OrderBook")
    void delist_removesSymbol() {
        // 先上币
        helper.beginRequest(0L, 100L);
        symbolService.listSymbol(3, "doge-usdt", 100, 200);
        symbolService.switchTrade(3, true);
        helper.endRequest();

        // 再下币
        helper.beginRequest(0L, 200L);
        symbolService.delistSymbol(3);
        List<MatchResult> events = helper.endRequest();

        // 验证全部清理
        assertNull(engine.getMatchEngineState().getMatchContext().getSymbolConfigMap().get(3));
        assertNull(engine.getMatchEngineState().getMatchContext().getOrderBook("doge-usdt"));
        assertNull(engine.getMatchEngineState().getMatchConfig("doge-usdt"));

        assertEquals(1, events.size());
        UpDownSymbolResult evt = (UpDownSymbolResult) events.get(0);
        assertEquals(SymbolOpEnum.DELIST, evt.getOp());
        assertEquals(ResultBizTypeEnum.SYMBOL_DOWN, evt.getResultBizType());
    }

    @Test
    @DisplayName("开启交易: MatchConfig.enabled = true + TradeSwitchResult 事件")
    void enableTrade() {
        helper.beginRequest(0L, 100L);
        symbolService.listSymbol(3, "doge-usdt", 100, 200);
        helper.endRequest();

        helper.beginRequest(0L, 200L);
        symbolService.switchTrade(3, true);
        List<MatchResult> events = helper.endRequest();

        MatchConfig mc = engine.getMatchEngineState().getMatchConfig("doge-usdt");
        assertTrue(mc.isEnabled());

        assertEquals(1, events.size());
        TradeSwitchResult evt = (TradeSwitchResult) events.get(0);
        assertTrue(evt.isSwitchOn());
        assertEquals(3, evt.getSymbolCode());
    }

    @Test
    @DisplayName("关闭交易: MatchConfig.enabled = false")
    void disableTrade() {
        helper.beginRequest(0L, 100L);
        symbolService.listSymbol(3, "doge-usdt", 100, 200);
        symbolService.switchTrade(3, true);
        helper.endRequest();

        helper.beginRequest(0L, 200L);
        symbolService.switchTrade(3, false);
        List<MatchResult> events = helper.endRequest();

        MatchConfig mc = engine.getMatchEngineState().getMatchConfig("doge-usdt");
        assertFalse(mc.isEnabled());

        TradeSwitchResult evt = (TradeSwitchResult) events.get(0);
        assertFalse(evt.isSwitchOn());
    }

    @Test
    @DisplayName("dogeUsdt 完整生命周期: 上币 → 开启 → 关闭 → 下币")
    void fullLifecycle_dogeUsdt() {
        // 1) 上币
        helper.beginRequest(0L, 100L);
        symbolService.listSymbol(3, "doge-usdt", 100, 200);
        List<MatchResult> e1 = helper.endRequest();
        assertEquals(SymbolOpEnum.LIST, ((UpDownSymbolResult) e1.get(0)).getOp());
        assertFalse(engine.getMatchEngineState().getMatchConfig("doge-usdt").isEnabled());

        // 2) 开启交易
        helper.beginRequest(0L, 200L);
        symbolService.switchTrade(3, true);
        List<MatchResult> e2 = helper.endRequest();
        assertTrue(((TradeSwitchResult) e2.get(0)).isSwitchOn());
        assertTrue(engine.getMatchEngineState().getMatchConfig("doge-usdt").isEnabled());

        // 3) 关闭交易
        helper.beginRequest(0L, 300L);
        symbolService.switchTrade(3, false);
        List<MatchResult> e3 = helper.endRequest();
        assertFalse(((TradeSwitchResult) e3.get(0)).isSwitchOn());
        assertFalse(engine.getMatchEngineState().getMatchConfig("doge-usdt").isEnabled());

        // 4) 下币
        helper.beginRequest(0L, 400L);
        symbolService.delistSymbol(3);
        List<MatchResult> e4 = helper.endRequest();
        assertEquals(SymbolOpEnum.DELIST, ((UpDownSymbolResult) e4.get(0)).getOp());
        assertNull(engine.getMatchEngineState().getMatchContext().getSymbolConfigMap().get(3));

        // 跨 4 个 batch 的 resultSerialNum 是连续的 1,2,3,4
        assertEquals(1L, e1.get(0).getResultSerialNum());
        assertEquals(2L, e2.get(0).getResultSerialNum());
        assertEquals(3L, e3.get(0).getResultSerialNum());
        assertEquals(4L, e4.get(0).getResultSerialNum());
    }

    @Test
    @DisplayName("上币幂等: 同 symbolCode 重复上币只生成事件不破坏 config")
    void list_idempotent() {
        helper.beginRequest(0L, 100L);
        symbolService.listSymbol(3, "doge-usdt", 100, 200);
        symbolService.switchTrade(3, true); // 启用
        helper.endRequest();

        helper.beginRequest(0L, 200L);
        symbolService.listSymbol(3, "doge-usdt", 100, 200);
        helper.endRequest();

        // 重复上币不应把 enabled 重置回 false
        assertTrue(engine.getMatchEngineState().getMatchConfig("doge-usdt").isEnabled(),
                "重复 LIST 不应重置已开启的 enabled");
    }

    @Test
    @DisplayName("下币不存在的币对: 不抛异常,仍发事件保持回放确定性")
    void delist_notExists_idempotent() {
        helper.beginRequest(0L, 100L);
        assertDoesNotThrow(() -> symbolService.delistSymbol(999));
        List<MatchResult> events = helper.endRequest();
        assertEquals(1, events.size(), "幂等: 仍发事件");
    }
}
