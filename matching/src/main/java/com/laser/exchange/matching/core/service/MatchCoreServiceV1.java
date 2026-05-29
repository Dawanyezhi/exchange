package com.laser.exchange.matching.core.service;

import com.laser.exchange.matching.core.model.DepthLineV1;
import com.laser.exchange.matching.core.model.MatchOrderV1;
import com.laser.exchange.matching.core.model.OrderBookV1;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;

/**
 * V1 撮合核心服务 — 枚举单例实现
 *
 * <p>与 V0 MatchCoreService 的差异：
 * <ul>
 *   <li>matchedQty 从 BigDecimal 变为 long，直接 Math.min 运算</li>
 *   <li>clearEmptyDepthLine 的 Iterator 泛型由 BigDecimal -> Long</li>
 *   <li>所有模型类型替换为 V1 版本</li>
 * </ul>
 *
 * <p>枚举单例优点：线程安全、防反射攻击、防序列化破坏、代码简洁</p>
 */
@Slf4j
public enum MatchCoreServiceV1 {

    INSTANCE;

    /**
     * 执行撮合：计算成交量、更新双方数量、移除已完全成交的对手单
     */
    public void doMatch(MatchOrderV1 newOrder, MatchOrderV1 oppoOrder,
                        Iterator<MatchOrderV1> orderIterator, OrderBookV1 orderBook) {

        // 计算成交数量 = min(新单剩余, 对手剩余)
        long matchedQty = Math.min(newOrder.getRemainingQuantity(), oppoOrder.getRemainingQuantity());

        // taker / maker 数量更新
        newOrder.updateFilledQuantity(matchedQty);
        oppoOrder.updateFilledQuantity(matchedQty);

        // 对手单完全成交 -> 移除订单簿
        if (oppoOrder.fullFilled()) {
            orderIterator.remove();
            orderBook.removeFromOrderMapOnly(oppoOrder.getOrderId());
        }
    }

    /**
     * 当档位被撮合空时删除该价格档位，保持数据一致性
     */
    public void clearEmptyDepthLine(DepthLineV1 depthLine,
                                    Iterator<Map.Entry<Long, DepthLineV1>> iterator) {
        if (depthLine.isEmpty()) {
            iterator.remove();
        }
    }
}
