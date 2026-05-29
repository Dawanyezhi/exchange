package com.laser.exchange.matching.core.model;

import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * 撮合价格档位
 */
@ToString
public class DepthLine {

    /**
     * 当前档位价格
     */
    @Getter
    private BigDecimal price;

    @Getter
    private List<MatchOrder> orders = new LinkedList<>();

    public DepthLine(BigDecimal price) {
        this.price = price;
    }

    /**
     * 将订单插入到价格档位
     * @param order
     */
    public void openOrder(MatchOrder order) {
        this.orders.add(order);
    }

    public void cancelOrder(MatchOrder order) {
        this.orders.remove(order);
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    /**
     * 深度档位迭代器
     * @return
     */
    public Iterator<MatchOrder> iterator() {
        return orders.iterator();
    }
}
