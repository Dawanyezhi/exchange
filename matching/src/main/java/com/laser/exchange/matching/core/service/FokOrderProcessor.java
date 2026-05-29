package com.laser.exchange.matching.core.service;

import com.laser.exchange.matching.core.model.DepthLine;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.matching.core.model.OrderBook;
import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.matching.enums.OpEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.Long2ObjectHashMap;

import java.math.BigDecimal;
import java.util.*;

/**
 * Fok订单类型的处理器
 */
@Slf4j
public class FokOrderProcessor {

    private StpStrategyService stpStrategyService = new StpStrategyService();

    /**
     * fok执行过程中需要撤单的列表及撤单原因
     */
    private List<MatchOrder> fokPendingRemoves =  new LinkedList<>();
    private Long2ObjectHashMap<CancelReasonEnum> fokCancelReasonMap = new Long2ObjectHashMap<>();


    public void process(MatchOrder newOrder, OrderBook orderBook) {

        try {

            // 价格交叉，不交叉撤单
            if (!orderBook.isCross(newOrder)) {
                newOrder.cancel(CancelReasonEnum.FOK_NOT_CROSS);
                return;
            }

            // 创建newOrder（fok订单）的深拷贝，进行预撮合，防止修改fok本身的属性
            MatchOrder copyOrder = newOrder.deepCopy();

            // 获取对手盘,迭代对手盘，如果价格交叉就预撮合，否则就退出
            TreeMap<BigDecimal, DepthLine> oppositeBook = orderBook.getOppositeBook(newOrder);
            Iterator<Map.Entry<BigDecimal, DepthLine>> iterator = oppositeBook.entrySet().iterator();

            boolean exit;
            while (iterator.hasNext()) {

                // 对手盘存在深度档位，判断是否交叉
                Map.Entry<BigDecimal, DepthLine> lineEntry = iterator.next();
                BigDecimal linePrice = lineEntry.getKey();
                DepthLine depthLine = lineEntry.getValue();

                // 深度档位为空
                if (depthLine.isEmpty()) {
                    log.warn("depthLine is empty. price:{}, symbol:{}", linePrice.toPlainString(), copyOrder.getSymbolId());
                    iterator.remove();
                    continue;
                }

                // 如果价格不交叉了 停止撮合
                if (!orderBook.isCross(copyOrder, linePrice)) {
                    break;
                }

                // phase1: 预撮合
                exit = this.fokPhase1(copyOrder, depthLine, orderBook, fokPendingRemoves, fokCancelReasonMap);

                if (exit) {
                    break;
                }
            }

            // fok不能完全成交，则撤销真实fok订单
            if (copyOrder.getOrderStatus() != OrderStatusEnum.FULL_FILLED) {
                newOrder.cancel(CancelReasonEnum.FOK_NOT_FULLFILL_CANCEL);
                return;
            }

            // phase2: 真实撮合, 先把需要撤销的进行撤单
            for (MatchOrder needCancelOrder : fokPendingRemoves) {
                needCancelOrder.cancel(fokCancelReasonMap.get(needCancelOrder.getOrderId()));
                orderBook.removeOrder(needCancelOrder);
            }

            // 进行撮合
            fokTryMatchInstantly(newOrder, orderBook);

        } finally {
            fokPendingRemoves.clear();
            fokCancelReasonMap.clear();
        }
    }

    private boolean fokPhase1(MatchOrder newOrder, DepthLine depthLine, OrderBook orderBook,
                              List<MatchOrder> fokPendingRemoves,
                              Long2ObjectHashMap<CancelReasonEnum> fokCancelReasonMap) {

        Iterator<MatchOrder> orderIterator = depthLine.iterator();

        boolean endMatch = false;

        while (orderIterator.hasNext()) {

            MatchOrder oppoOrder = orderIterator.next();

            // 兜底：对手单必须存在
            if (oppoOrder == null) {
                endMatch = true;
                return endMatch;
            }

            // 兜底：对手单必须是限价单
            if (oppoOrder.isMarket()) {
                endMatch = true;
                return endMatch;
            }

            // [实战中可能需要考虑]：市价单价格滑点，超出滑点不能撮合；
            // [实战中可能需要考虑]: 自成交保护，如果是同一个用户下单，并且价格交叉，开启了配置：需要进行自成交保护，那么就会跳过同一个人的订单
            OpEnum opEnum = stpStrategyService.processSTP(newOrder, oppoOrder, null);
            if (opEnum == OpEnum.OP_BREAK) {
                // 当前订单撤销，终止撮合
                endMatch = true;
                return endMatch;
            }
            if (opEnum == OpEnum.OP_CONTINUE) {
                // 对手单因为自成交保护需要撤销
                fokPendingRemoves.add(oppoOrder);
                fokCancelReasonMap.put(oppoOrder.getOrderId(), CancelReasonEnum.STP_CANCEL);
                continue;
            }

            // 进行预成交，判断fok是否能够完全成交
            boolean isFokFullFill = isFokFullFill(newOrder, oppoOrder);

            // 当前fok可以完全成交，返回进行真正的成交，不终止撮合进程
            if (isFokFullFill) {
                return endMatch;
            }
        }

        return endMatch;
    }

    private void fokTryMatchInstantly(MatchOrder newOrder, OrderBook orderBook) {

        // 获取对手盘
        TreeMap<BigDecimal, DepthLine> oppositeBook = orderBook.getOppositeBook(newOrder);

        // 迭代对手盘，如果价格交叉就撮合，否则就退出
        // 撮合过程中需要修改深度中的订单
        Iterator<Map.Entry<BigDecimal, DepthLine>> iterator = oppositeBook.entrySet().iterator();

        boolean exit;

        while (iterator.hasNext()) {
            // 对手盘存在深度档位，判断是否交叉
            Map.Entry<BigDecimal, DepthLine> lineEntry = iterator.next();
            BigDecimal linePrice = lineEntry.getKey();
            DepthLine depthLine = lineEntry.getValue();

            if (depthLine.isEmpty()) {
                log.warn("depthLine is empty. price:{}, symbol:{}", linePrice.toPlainString(), newOrder.getSymbolId());
                iterator.remove();
                continue;
            }

            if (!orderBook.isCross(newOrder, linePrice)) {
                break;
            }

            exit = this.fokDoMatch(newOrder, depthLine, orderBook);

            // 如果当前价格档位为empty，则移除
            MatchCoreService.INSTANCE.clearEmptyDepthLine(depthLine, iterator);

            if (exit) {
                break;
            }
        }

        if (newOrder.getOrderStatus() != OrderStatusEnum.FULL_FILLED) {
            log.error("fatal! FOK should full filled but not! status:{}, id:{}", newOrder.getOrderStatus(), newOrder.getOrderId());
        }

    }

    private boolean fokDoMatch(MatchOrder newOrder, DepthLine depthLine, OrderBook orderBook) {

        Iterator<MatchOrder> orderIterator = depthLine.iterator();

        boolean endMatch = false;

        while (orderIterator.hasNext()) {

            MatchOrder oppoOrder = orderIterator.next();

            // 成交
            MatchCoreService.INSTANCE.doMatch(newOrder, oppoOrder, orderIterator, orderBook);

            // 当前订单是否撮合完成
            if (newOrder.isMatchOver()) {
                endMatch = true;
                return endMatch;
            }
        }

        return endMatch;
    }


    /**
     * 模拟成交过程，不修改对手单状态，如果fok 不能完全成交则撤单
     * 否则如果能完全成交，就在后续的过程执行真实成交
     * @param copyOrder
     * @param oppoOrder
     * @return
     */
    private boolean isFokFullFill(MatchOrder copyOrder, MatchOrder oppoOrder) {

        // 计算成交数量，两者中数量较小一方
        BigDecimal matchedQty = copyOrder.getMatchedQty(oppoOrder.getRemainingQuantity());

        // 累加当前订单的成交量（copyOrder的属性，所以不用担心修改原有的真实订单的属性）
        // 也不可以直接在这里修改对手单的成交量，确保fok能完全成交，在phase2的真实成交过程，再去修改
        copyOrder.updateFilledQuantity(matchedQty);

        // 返回是否可以完全成交
        boolean fullFilled = copyOrder.fullFilled();
        if (fullFilled) {
            copyOrder.setOrderStatus(OrderStatusEnum.FULL_FILLED);
            return true;
        }
        return false;
    }
}
