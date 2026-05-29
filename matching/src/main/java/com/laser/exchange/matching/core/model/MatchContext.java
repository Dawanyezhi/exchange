package com.laser.exchange.matching.core.model;

import com.laser.exchange.common.config.SymbolConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Object2ObjectHashMap;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 撮合上下文，币对维度（交易对维度）
 * 核心：通过币对名称获取到深度
 */
@Slf4j
public class MatchContext {

    @Getter
    private Map<Integer, SymbolConfig> symbolConfigMap = new Int2ObjectHashMap<>();

    @Getter
    private Map<String, OrderBook> orderBookMap = new Object2ObjectHashMap<>();


    public void initContext() {
        // todo 启动过程加载币对上下文

        // 恢复快照，从快照中读到当前深度，恢复orderBookMap
    }

    public void addOrderBook(OrderBook orderBook) {
        this.orderBookMap.put(orderBook.getSymbol(),  orderBook);
    }

    public OrderBook getOrderBook(String symbol) {
        return orderBookMap.get(symbol);
    }

    public void addSymbol(Integer symbolCode, SymbolConfig symbolConfig) {
        symbolConfigMap.computeIfAbsent(symbolCode, integer -> symbolConfig);
    }

    public void removeSymbol(Integer symbolCode) {
        SymbolConfig removed = symbolConfigMap.remove(symbolCode);
        if (removed != null) {
            orderBookMap.remove(removed.getSymbolName());
        }
    }

    public String getSymbolNameByCode(Integer symbolCode) {
        SymbolConfig symbolConfig = symbolConfigMap.get(symbolCode);
        if (Objects.isNull(symbolConfig)) {
            return null;
        }
        return symbolConfig.getSymbolName();
    }
}
