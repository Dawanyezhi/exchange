package com.laser.exchange.matching.core.service;

import com.laser.exchange.common.config.SymbolConfig;
import com.laser.exchange.common.enums.SymbolOpEnum;
import com.laser.exchange.matching.core.engine.MatchEngine;
import com.laser.exchange.matching.core.model.MatchConfig;
import com.laser.exchange.matching.core.model.MatchContext;
import com.laser.exchange.matching.core.model.MatchEngineState;
import com.laser.exchange.matching.core.model.OrderBook;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 上下币 / 开关交易 控制面服务。
 *
 * <p><b>职责</b>：维护 {@link MatchEngineState} 中 symbolConfigMap / configMap / orderBookMap 三者一致性。
 *
 * <p><b>状态机线程</b>：所有方法都在 cluster 状态机单线程内调用，无并发；和数据面 (place/cancel) 共享一个线程。
 *
 * <p><b>事件生成</b>：每个操作通过 {@link MatchResultEventsHelper} 产生 {@code UpDownSymbolResult}
 * 或 {@code TradeSwitchResult} 投递到下游。
 */
@Slf4j
@Component
public class SymbolService {

    @Resource
    private MatchEngine matchEngine;

    @Resource
    private MatchResultEventsHelper eventsHelper;

    /**
     * 上币：注册 SymbolConfig + 创建 OrderBook + 默认 disabled 的 MatchConfig。
     *
     * <p>幂等：如果 symbolCode 已存在，记 warn 日志但仍然产生 result（便于 raftlog 回放确定性）。
     */
    public void listSymbol(int symbolCode, String symbolName, long baseCoinId, long quoteCoinId) {
        MatchEngineState state = matchEngine.getMatchEngineState();
        MatchContext ctx = state.getMatchContext();

        SymbolConfig existing = ctx.getSymbolConfigMap().get(symbolCode);
        if (existing != null) {
            log.warn("[SymbolService] listSymbol idempotent: symbolCode={} already exists, skip", symbolCode);
        } else {
            SymbolConfig cfg = new SymbolConfig();
            cfg.setSymbolId(symbolCode);
            cfg.setSymbolName(symbolName);
            cfg.setSymbolDisplayName(symbolName);
            cfg.setBaseCoinId((int) baseCoinId);
            cfg.setQuoteCoinId((int) quoteCoinId);
            ctx.addSymbol(symbolCode, cfg);

            // 预创建 orderBook，避免首笔下单时延迟创建
            ctx.addOrderBook(new OrderBook(symbolName));

            // 默认 disabled，需要显式 enable-trade 才能交易
            MatchConfig mc = new MatchConfig();
            mc.setSymbol(symbolName);
            mc.setEnabled(false);
            mc.setMarketOrderProtectionBps(matchEngine.getDefaultMarketOrderProtectionBps());
            state.addMatchConfig(mc);

            log.info("[SymbolService] LIST symbol: code={}, name={}, base={}, quote={} (default disabled)",
                    symbolCode, symbolName, baseCoinId, quoteCoinId);
        }

        eventsHelper.appendUpDownSymbol(SymbolOpEnum.LIST, symbolCode, symbolName,
                baseCoinId, quoteCoinId);
    }

    /**
     * 下币：从上下文移除 symbolConfig 和 orderBook，并把 matchConfig.enabled 置为 false (再删除)。
     */
    public void delistSymbol(int symbolCode) {
        MatchEngineState state = matchEngine.getMatchEngineState();
        MatchContext ctx = state.getMatchContext();

        SymbolConfig cfg = ctx.getSymbolConfigMap().get(symbolCode);
        if (cfg == null) {
            log.warn("[SymbolService] delistSymbol: symbolCode={} not exists, skip", symbolCode);
            eventsHelper.appendUpDownSymbol(SymbolOpEnum.DELIST, symbolCode, "", 0, 0);
            return;
        }
        String name = cfg.getSymbolName();
        long baseCoinId = cfg.getBaseCoinId();
        long quoteCoinId = cfg.getQuoteCoinId();

        // 先关停交易
        MatchConfig mc = state.getMatchConfig(name);
        if (mc != null) {
            mc.setEnabled(false);
        }
        state.removeMatchConfig(name);

        // 移除 SymbolConfig + OrderBook
        ctx.removeSymbol(symbolCode);

        log.info("[SymbolService] DELIST symbol: code={}, name={}", symbolCode, name);

        eventsHelper.appendUpDownSymbol(SymbolOpEnum.DELIST, symbolCode, name, baseCoinId, quoteCoinId);
    }

    /**
     * 开/关交易：仅切换 MatchConfig.enabled，不动 symbolConfig 也不动 orderBook。
     */
    public void switchTrade(int symbolCode, boolean switchOn) {
        MatchEngineState state = matchEngine.getMatchEngineState();
        SymbolConfig cfg = state.getMatchContext().getSymbolConfigMap().get(symbolCode);
        if (cfg == null) {
            log.warn("[SymbolService] switchTrade: symbolCode={} not exists, skip", symbolCode);
            // 仍发事件，符合幂等
            eventsHelper.appendTradeSwitch(symbolCode, "", switchOn);
            return;
        }
        String name = cfg.getSymbolName();
        MatchConfig mc = state.getMatchConfig(name);
        if (mc == null) {
            mc = new MatchConfig();
            mc.setSymbol(name);
            mc.setMarketOrderProtectionBps(matchEngine.getDefaultMarketOrderProtectionBps());
            state.addMatchConfig(mc);
        }
        mc.setEnabled(switchOn);
        log.info("[SymbolService] TRADE_SWITCH symbol: code={}, name={}, switchOn={}",
                symbolCode, name, switchOn);

        eventsHelper.appendTradeSwitch(symbolCode, name, switchOn);
    }
}
