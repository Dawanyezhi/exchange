package com.laser.exchange.matching.core.model;

import lombok.Getter;
import org.agrona.collections.Object2ObjectHashMap;

import java.util.Map;

/**
 * 撮合引擎单线程模式主内存
 * Aeron cluster： onSessionMessage 通过MatchEngineState 进行业务操作
 * 放币对的配置：
 * - 币对是否开启交易
 * - 币对的精度调整，上下币
 * - 是否支持做市商快速下单 铺单
 */
public class MatchEngineState {

    @Getter
    private Map<String, MatchConfig> configMap =  new Object2ObjectHashMap<>();

    @Getter
    private MatchContext matchContext = new MatchContext();

    public MatchConfig getMatchConfig(String symbol) {
        return configMap.get(symbol);
    }

    public void addMatchConfig(MatchConfig config) {
        configMap.put(config.getSymbol(), config);
    }

    public void removeMatchConfig(String symbol) {
        configMap.remove(symbol);
    }

}
