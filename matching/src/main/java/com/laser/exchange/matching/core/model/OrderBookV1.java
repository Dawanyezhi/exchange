package com.laser.exchange.matching.core.model;

import com.laser.exchange.common.enums.OrderSideEnum;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

/**
 * V1 撮合订单簿 — TreeMap key 由 BigDecimal 替换为 Long
 *
 * <p>核心结构：
 * <ul>
 *   <li>买单订单簿 bid — 价格从高到低（TreeMap 降序）</li>
 *   <li>卖单订单簿 ask — 价格从低到高（TreeMap 升序）</li>
 * </ul>
 *
 * <p>与 V0 OrderBook 的差异：
 * <ul>
 *   <li>TreeMap&lt;BigDecimal, DepthLine&gt; -> TreeMap&lt;Long, DepthLineV1&gt;</li>
 *   <li>MatchOrder -> MatchOrderV1</li>
 *   <li>isCross 使用 long 直接比较，消除 compareTo 调用</li>
 *   <li>getBestAskPrice / getBestBidPrice 返回 Long（null 表示空盘）</li>
 *   <li>移除 printOrderBook 方法（热路径优化）</li>
 * </ul>
 */
@Slf4j
@ToString
public class OrderBookV1 {

    @Getter
    private String symbol;

    /**
     * 买单簿 — 逆序定义，firstKey 为最优买价
     */
    @Getter
    private TreeMap<Long, DepthLineV1> buyOrders = new TreeMap<>(Collections.reverseOrder());

    /**
     * 卖单簿 — 顺序定义，firstKey 为最优卖价
     */
    @Getter
    private TreeMap<Long, DepthLineV1> sellOrders = new TreeMap<>();

    /**
     * 市价买单队列
     */
    @Getter
    private LinkedList<MatchOrderV1> buyMarketOrderQueue = new LinkedList<>();

    /**
     * 市价卖单队列
     */
    @Getter
    private LinkedList<MatchOrderV1> sellMarketOrderQueue = new LinkedList<>();

    /**
     * 订单全局映射（使用 Agrona Long2ObjectHashMap，减少自动装箱）
     */
    @Getter
    private Map<Long, MatchOrderV1> orderMap = new Long2ObjectHashMap<>();

    public OrderBookV1(String symbol) {
        this.symbol = symbol;
    }

    public boolean isOrderExists(long orderId) {
        return orderMap.get(orderId) != null;
    }

    public MatchOrderV1 getOrder(long orderId) {
        return orderMap.get(orderId);
    }

    // ======================== 下单 ========================

    /**
     * 添加订单到订单簿
     */
    public void addOrder(MatchOrderV1 order) {
        if (order.isLimit()) {
            TreeMap<Long, DepthLineV1> book = getBook(order);

            // long key，computeIfAbsent 直接使用原始类型（Agrona map 不装箱）
            DepthLineV1 depthLine = book.computeIfAbsent(order.getDelegatePrice(),
                    k -> new DepthLineV1(order.getDelegatePrice()));

            depthLine.openOrder(order);
            orderMap.put(order.getOrderId(), order);

            log.debug("[订单簿-挂单] symbol={}, orderId={}, side={}, price={}, qty={}",
                    symbol, order.getOrderId(), order.getOrderSide(),
                    order.getDelegatePrice(), order.getDelegateCount());
        } else {
            sendMarketOrder(order);
        }
    }

    /**
     * 市价单放到对应的队列中
     */
    void sendMarketOrder(MatchOrderV1 order) {
        if (order.isBuy()) {
            this.buyMarketOrderQueue.add(order);
        } else {
            this.sellMarketOrderQueue.add(order);
        }
    }

    // ======================== 撤单 ========================

    /**
     * 从订单簿移除订单
     */
    public void removeOrder(MatchOrderV1 order) {
        if (order.isLimit()) {
            long orderId = order.getOrderId();
            if (!isOrderExists(orderId)) {
                log.warn("[订单簿-撤单] 订单不存在, symbol={}, orderId={}", symbol, orderId);
                return;
            }

            TreeMap<Long, DepthLineV1> book = getBook(order);
            long delegatePrice = order.getDelegatePrice();
            DepthLineV1 depthLine = book.get(delegatePrice);

            if (depthLine != null) {
                depthLine.cancelOrder(order);
                if (depthLine.isEmpty()) {
                    book.remove(delegatePrice);
                }
            }

            orderMap.remove(orderId);
        } else {
            removeMarketOrder(order);
        }
    }

    void removeMarketOrder(MatchOrderV1 order) {
        LinkedList<MatchOrderV1> marketOrderQueue = getMarketOrderQueue(order);
        marketOrderQueue.remove(order);
    }

    /**
     * 撮合过程中仅从全局映射移除订单
     */
    public void removeFromOrderMapOnly(long orderId) {
        orderMap.remove(orderId);
    }

    // ======================== 改单 ========================

    /**
     * 改单 = 撤旧单 + 下新单
     */
    public void amendOrder(MatchOrderV1 amendedOrder, MatchOrderV1 originalOrder) {
        removeOrder(originalOrder);
        addOrder(amendedOrder);
    }

    // ======================== 买卖盘获取 ========================

    public TreeMap<Long, DepthLineV1> getBook(MatchOrderV1 order) {
        if (order.getOrderSide() == OrderSideEnum.BUY) {
            return buyOrders;
        }
        return sellOrders;
    }

    public TreeMap<Long, DepthLineV1> getOppositeBook(MatchOrderV1 order) {
        if (order.getOrderSide() == OrderSideEnum.BUY) {
            return sellOrders;
        }
        return buyOrders;
    }

    // ======================== 价格交叉判断（long 直接比较）========================

    /**
     * 检查订单是否与对手盘最优价发生价格交叉
     */
    public boolean isCross(MatchOrderV1 matchOrder) {
        if (matchOrder.isMarket()) {
            return true;
        }
        if (matchOrder.isBuy()) {
            Long bestAsk = getBestAskPrice();
            return bestAsk != null && matchOrder.getDelegatePrice() >= bestAsk;
        } else {
            Long bestBid = getBestBidPrice();
            return bestBid != null && matchOrder.getDelegatePrice() <= bestBid;
        }
    }

    /**
     * 撮合过程判断档位价格是否和订单交叉
     */
    public boolean isCross(MatchOrderV1 matchOrder, Long linePrice) {
        if (linePrice == null) {
            return false;
        }
        if (matchOrder.isMarket()) {
            return true;
        }
        if (matchOrder.isBuy()) {
            return matchOrder.getDelegatePrice() >= linePrice;
        } else {
            return matchOrder.getDelegatePrice() <= linePrice;
        }
    }

    // ======================== 最优价 ========================

    /**
     * 最优卖价（null 表示卖盘为空）
     */
    public Long getBestAskPrice() {
        return sellOrders.isEmpty() ? null : sellOrders.firstKey();
    }

    /**
     * 最优买价（null 表示买盘为空）
     */
    public Long getBestBidPrice() {
        return buyOrders.isEmpty() ? null : buyOrders.firstKey();
    }

    // ======================== 市价单调度 ========================

    /**
     * 判断市价单是否可以直接撮合（队列为空时可立即撮合）
     */
    public boolean marketOrderMatchInstantly(MatchOrderV1 marketOrder) {
        LinkedList<MatchOrderV1> marketOrderQueue = getMarketOrderQueue(marketOrder);
        return marketOrderQueue.isEmpty();
    }

    private LinkedList<MatchOrderV1> getMarketOrderQueue(MatchOrderV1 marketOrder) {
        return marketOrder.isBuy() ? this.buyMarketOrderQueue : this.sellMarketOrderQueue;
    }
}
