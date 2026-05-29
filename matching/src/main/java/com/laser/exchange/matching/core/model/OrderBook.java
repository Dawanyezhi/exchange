package com.laser.exchange.matching.core.model;

import com.alibaba.fastjson.JSON;
import com.laser.exchange.common.enums.OrderSideEnum;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.Long2ObjectHashMap;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

/**
 * 撮合订单簿
 *
 * 核心结构：
 *  买单订单簿 bid  价格从高到低 TreeMap降序 逆序
 *  卖单订单簿 ask  价格从低到高 TreeMap升序
 *
 *  map key: 价格 value: 对应深度档位
 *
 *  遵循：时间优先，价格优先
 */
@Slf4j
@ToString
public class OrderBook {

    @Getter
    private String symbol;

    /**
     * 买单簿
     * 逆序定义，确保firstKey是最优买价
     */
    @Getter
    private TreeMap<BigDecimal, DepthLine> buyOrders = new TreeMap<>(Collections.reverseOrder());

    /**
     * 卖单簿
     * 顺序定义，确保firstKey是最优卖价
     */
    @Getter
    private TreeMap<BigDecimal, DepthLine> sellOrders =  new TreeMap<>();

    /**
     * 市价买单队列
     */
    @Getter
    private LinkedList<MatchOrder> buyMarketOrderQueue = new LinkedList<>();

    /**
     * 市价卖单队列
     */
    @Getter
    private LinkedList<MatchOrder> sellMarketOrderQueue = new LinkedList<>();

    /**
     * 订单全局映射
     */
    @Getter
    private Map<Long, MatchOrder> orderMap = new Long2ObjectHashMap<>();


    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public boolean isOrderExists(long orderId) {
        return orderMap.get(orderId) != null;
    }

    public MatchOrder getOrder(long orderId) {
        return orderMap.get(orderId);
    }

    /**
     * 添加订单到订单簿：下单
     * @param order
     */
    public void addOrder(MatchOrder order) {

        if (order.isLimit()) {
            // 获取买卖盘
            TreeMap<BigDecimal, DepthLine> book = getBook(order);

            // 获取价格档位
            DepthLine depthLine = book.computeIfAbsent(order.getDelegatePrice(), k -> new DepthLine(order.getDelegatePrice()));

            // 时间优先，加入到档位
            depthLine.openOrder(order);

            // 维护全局映射
            orderMap.put(order.getOrderId(), order);

            log.debug("[订单簿-挂单] symbol={}, orderId={}, side={}, price={}, qty={}",
                    symbol, order.getOrderId(), order.getOrderSide(),
                    order.getDelegatePrice(), order.getDelegateCount());
        } else {
            // 市价单放到一个队列
            sendMarketOrder(order);
        }

    }

    /**
     * 市价单放到对应的队列中
     * @param order
     */
    void sendMarketOrder(MatchOrder order) {
        if (order.isBuy()) {
            this.buyMarketOrderQueue.add(order);
        } else {
            this.sellMarketOrderQueue.add(order);
        }
    }

    /**
     * 撤单
     * @param order
     */
    public void removeOrder(MatchOrder order) {

        if (order.isLimit()) {
            long orderId = order.getOrderId();
            if (!isOrderExists(orderId)) {
                log.warn("[订单簿-撤单] 订单不存在, symbol={}, orderId={}", symbol, orderId);
                return;
            }

            // 先移除深度档位
            TreeMap<BigDecimal, DepthLine> book = getBook(order);
            BigDecimal delegatePrice = order.getDelegatePrice();
            DepthLine depthLine = book.get(delegatePrice);

            // 只在存在当前订单价格档位时才移除订单
            if (depthLine != null) {
                depthLine.cancelOrder(order);
                // 如果发现这个价格档位已经不存在订单了，就直接移除该价格档位，防止存在空档位
                if (depthLine.isEmpty()) {
                    book.remove(delegatePrice);
                }
            }

            // 再移除全局映射
            orderMap.remove(orderId);

        } else {
            removeMarketOrder(order);
        }
    }

    void removeMarketOrder(MatchOrder order) {
        LinkedList<MatchOrder> marketOrderQueue = getMarketOrderQueue(order);
        marketOrderQueue.remove(order);
    }

    /**
     * 撮合过程中从映射中移除订单
     * @param orderId
     */
    public void removeFromOrderMapOnly(long orderId) {
        orderMap.remove(orderId);
    }

    /**
     * 改单本质 组合撤单下单
     * 只改价格，不改数量 : 切换价格档位，重新下单（去掉原档位的引用，下到一个新档位，更新orderMap映射）
     * 只改数量，不改价格 : 撤原有单（移除当前档位引用） 下到当前档位
     * 价格数量都改：同上
     * @param amendedOrder
     */
    public void amendOrder(MatchOrder amendedOrder, MatchOrder originalOrder) {

        removeOrder(originalOrder);

        // 用新属性下单
        addOrder(amendedOrder);
    }



    /**
     * 获取当前方向
     * @param order
     * @return
     */
    public TreeMap<BigDecimal, DepthLine> getBook(MatchOrder order) {
        if (order.getOrderSide() == OrderSideEnum.BUY) {
            return buyOrders;
        }
        return sellOrders;
    }

    /**
     * 获取对手盘
     * @param order
     * @return
     */
    public TreeMap<BigDecimal, DepthLine> getOppositeBook(MatchOrder order) {
        if (order.getOrderSide() == OrderSideEnum.BUY) {
            return sellOrders;
        }
        return buyOrders;
    }

    /**
     * 检查是否可以立即成交 即判断价格是否与对手盘最优价发生价格交叉
     * @param matchOrder
     * @return
     */
    public boolean isCross(MatchOrder matchOrder) {

        if (matchOrder.isMarket()) {
            return true;
        }

        if (matchOrder.isBuy()) {
            // 买单和卖盘交叉的条件：卖盘价格存在并且买单价格大于等于卖盘最优价
            BigDecimal bestAsk = getBestAskPrice();
            return bestAsk != null && matchOrder.getDelegatePrice().compareTo(bestAsk) >= 0;
        } else {
            // 卖单和买盘交叉的条件：买盘存在并且卖单价格小于等于买盘最优价
            BigDecimal bestBid = getBestBidPrice();
            return bestBid != null && matchOrder.getDelegatePrice().compareTo(bestBid) <= 0;
        }
    }

    /**
     * 撮合过程判断档位价格是否和订单交叉
     * @param matchOrder
     * @param linePrice
     * @return
     */
    public boolean isCross(MatchOrder matchOrder, BigDecimal linePrice) {

        if (linePrice == null) {
            return false;
        }

        if (matchOrder.isMarket()) {
            return true;
        }

        if (matchOrder.isBuy()) {
            // 买单和卖盘交叉的条件：卖盘价格存在并且买单价格大于等于卖盘最优价
            return matchOrder.getDelegatePrice().compareTo(linePrice) >= 0;
        } else {
            // 卖单和买盘交叉的条件：买盘存在并且卖单价格小于等于买盘最优价
            return matchOrder.getDelegatePrice().compareTo(linePrice) <= 0;
        }
    }

    /**
     * 最优卖价
     * @return
     */
    public BigDecimal getBestAskPrice() {
        return sellOrders.isEmpty() ? null : sellOrders.firstKey();
    }

    /**
     * 最优买价
     * @return
     */
    public BigDecimal getBestBidPrice() {
        return buyOrders.isEmpty() ? null : buyOrders.firstKey();
    }

    public void printOrderBook(String scene) {
        log.info("{} buyOrders: {} sellOrders:{} \nbuyOne:{}, sellOne:{}", scene, buyOrders.size(), sellOrders.size(),
                JSON.toJSONString(buyOrders.firstEntry(), true), JSON.toJSONString(sellOrders.firstEntry(), true));
    }

    /**
     * 如果当前市价单是队列中的第一个订单，就可以直接撮合
     * 否则就等待调度
     * @param marketOrder
     * @return
     */
    public boolean marketOrderMatchInstantly(MatchOrder marketOrder) {
        LinkedList<MatchOrder> marketOrderQueue = getMarketOrderQueue(marketOrder);
        return marketOrderQueue.isEmpty();
    }

    private LinkedList<MatchOrder> getMarketOrderQueue(MatchOrder marketOrder) {
        return marketOrder.isBuy() ? this.buyMarketOrderQueue : this.sellMarketOrderQueue;
    }
}
