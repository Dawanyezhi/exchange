package com.laser.exchange.matching.core.service;

import com.alibaba.fastjson.JSON;
import com.laser.exchange.matching.core.model.DepthLine;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.matching.core.model.OrderBook;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;

/**
 * 撮合核心服务 - 枚举单例实现
 *
 * 优点：
 * 1. 线程安全（JVM 保证枚举实例的唯一性）
 * 2. 防止反射攻击
 * 3. 防止序列化破坏单例
 * 4. 代码简洁
 */
@Slf4j
public enum MatchCoreService {

    INSTANCE;

    public void doMatch(MatchOrder newOrder, MatchOrder oppoOrder, Iterator<MatchOrder> orderIterator, OrderBook orderBook) {

        // 计算成交数量，两者中数量较小一方
        BigDecimal matchedQty = newOrder.getMatchedQty(oppoOrder.getRemainingQuantity());

        // taker maker数量更新
        newOrder.updateFilledQuantity(matchedQty);
        oppoOrder.updateFilledQuantity(matchedQty);

        // 属性修改 [对手单完全成交] 移除订单簿
        if (oppoOrder.fullFilled()) {
            orderIterator.remove();
            orderBook.removeFromOrderMapOnly(oppoOrder.getOrderId());
        }
    }

    /**
     * 当档位被撮合空，则删除该空的价格档位，保持数据的一致性
     * @param depthLine
     * @param iterator
     */
    public void clearEmptyDepthLine(DepthLine depthLine, Iterator<Map.Entry<BigDecimal, DepthLine>> iterator) {
        if (depthLine.isEmpty()) {
            iterator.remove();
        }
    }
}
