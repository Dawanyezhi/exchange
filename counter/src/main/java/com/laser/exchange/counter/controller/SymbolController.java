package com.laser.exchange.counter.controller;

import com.laser.exchange.counter.service.SymbolService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制面 REST：上下币 / 开关交易。
 *
 * <p><b>不参与 serialNum 校验</b>，与 OrderController (数据面) 分离。
 */
@Slf4j
@RestController
@RequestMapping("/api/symbol")
public class SymbolController {

    @Resource
    private SymbolService symbolService;

    /** 上币 — 注册币对，默认 enabled=false，需要再调用 enable 才能交易 */
    @PostMapping("/list")
    public Map<String, Object> listSymbol(
            @RequestParam("symbolCode") int symbolCode,
            @RequestParam("symbolName") String symbolName,
            @RequestParam("baseCoinId") long baseCoinId,
            @RequestParam("quoteCoinId") long quoteCoinId) {
        boolean ok = symbolService.listSymbol(symbolCode, symbolName, baseCoinId, quoteCoinId);
        return resp(ok, "list", symbolCode, symbolName);
    }

    /** 下币 — 移除币对配置 + 关闭交易 */
    @PostMapping("/delist")
    public Map<String, Object> delistSymbol(@RequestParam("symbolCode") int symbolCode) {
        boolean ok = symbolService.delistSymbol(symbolCode);
        return resp(ok, "delist", symbolCode, null);
    }

    /** 开启交易 — 设置 MatchConfig.enabled=true */
    @PostMapping("/enable-trade")
    public Map<String, Object> enableTrade(@RequestParam("symbolCode") int symbolCode) {
        boolean ok = symbolService.enableTrade(symbolCode);
        return resp(ok, "enable-trade", symbolCode, null);
    }

    /** 关闭交易 — 设置 MatchConfig.enabled=false */
    @PostMapping("/disable-trade")
    public Map<String, Object> disableTrade(@RequestParam("symbolCode") int symbolCode) {
        boolean ok = symbolService.disableTrade(symbolCode);
        return resp(ok, "disable-trade", symbolCode, null);
    }

    private Map<String, Object> resp(boolean success, String action, int symbolCode, String symbolName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", success);
        body.put("action", action);
        body.put("symbolCode", symbolCode);
        if (symbolName != null) {
            body.put("symbolName", symbolName);
        }
        return body;
    }
}
