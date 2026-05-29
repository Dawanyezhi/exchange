package com.laser.exchange.matching.core.model;

import lombok.Getter;
import lombok.ToString;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * V1 撮合价格档位 — 使用 long 价格
 *
 * <p>与 V0 DepthLine 的差异：
 * <ul>
 *   <li>BigDecimal price -> long price</li>
 *   <li>保留 LinkedList（撮合热路径 iterator.remove() 为 O(1)，
 *       生产环境同档位可达数万订单，ArrayList.remove 会 O(n) 搬迁元素）</li>
 *   <li>订单类型从 MatchOrder -> MatchOrderV1</li>
 * </ul>
 */
@ToString
public class DepthLineV1 {

    @Getter
    private long price;

    @Getter
    private List<MatchOrderV1> orders = new LinkedList<>();

    public DepthLineV1(long price) {
        this.price = price;
    }

    public void openOrder(MatchOrderV1 order) {
        this.orders.add(order);
    }

    public void cancelOrder(MatchOrderV1 order) {
        this.orders.remove(order);
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public Iterator<MatchOrderV1> iterator() {
        return orders.iterator();
    }
}
