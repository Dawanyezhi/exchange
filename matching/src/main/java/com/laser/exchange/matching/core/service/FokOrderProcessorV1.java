package com.laser.exchange.matching.core.service;

import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.matching.core.model.DepthLineV1;
import com.laser.exchange.matching.core.model.MatchOrderV1;
import com.laser.exchange.matching.core.model.OrderBookV1;
import com.laser.exchange.matching.enums.OpEnum;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.*;

/**
 * V1 FOK 订单处理器 — 两阶段撮合（预撮合 + 真实撮合）
 *
 * <p>与 V0 FokOrderProcessor 的差异：
 * <ul>
 *   <li>所有 BigDecimal 类型替换为 long</li>
 *   <li>MatchOrder -> MatchOrderV1, DepthLine -> DepthLineV1, OrderBook -> OrderBookV1</li>
 *   <li>fokPendingRemoves 使用 ArrayList 替代 LinkedList（减少指针追逐开销）</li>
 *   <li>TreeMap key 从 BigDecimal -> Long</li>
 * </ul>
 *
 * <p>业务逻辑与 V0 完全一致：Phase1 预撮合判断能否完全成交，Phase2 真实撮合执行。</p>
 */
@Slf4j
public class FokOrderProcessorV1 {

    private StpStrategyServiceV1 stpStrategyService = new StpStrategyServiceV1();

    /**
     * FOK 执行过程中需要撤单的列表及撤单原因
     */
    private List<MatchOrderV1> fokPendingRemoves = new ArrayList<>();
    private Long2ObjectHashMap<CancelReasonEnum> fokCancelReasonMap = new Long2ObjectHashMap<>();

    public void process(MatchOrderV1 newOrder, OrderBookV1 orderBook) {

        try {
            // 价格交叉判断，不交叉则撤单
            if (!orderBook.isCross(newOrder)) {
                newOrder.cancel(CancelReasonEnum.FOK_NOT_CROSS);
                return;
            }

            // 深拷贝进行预撮合，防止修改 FOK 本身的属性
            MatchOrderV1 copyOrder = newOrder.deepCopy();

            // 获取对手盘，迭代深度档位
            TreeMap<Long, DepthLineV1> oppositeBook = orderBook.getOppositeBook(newOrder);
            Iterator<Map.Entry<Long, DepthLineV1>> iterator = oppositeBook.entrySet().iterator();

            boolean exit;
            while (iterator.hasNext()) {

                Map.Entry<Long, DepthLineV1> lineEntry = iterator.next();
                long linePrice = lineEntry.getKey();
                DepthLineV1 depthLine = lineEntry.getValue();

                // 深度档位为空
                if (depthLine.isEmpty()) {
                    log.warn("depthLine is empty. price:{}, symbol:{}", linePrice, copyOrder.getSymbolId());
                    iterator.remove();
                    continue;
                }

                // 价格不交叉则停止
                if (!orderBook.isCross(copyOrder, linePrice)) {
                    break;
                }

                // Phase1: 预撮合
                exit = this.fokPhase1(copyOrder, depthLine, orderBook, fokPendingRemoves, fokCancelReasonMap);
                if (exit) {
                    break;
                }
            }

            // FOK 不能完全成交，撤销真实订单
            if (copyOrder.getOrderStatus() != OrderStatusEnum.FULL_FILLED) {
                newOrder.cancel(CancelReasonEnum.FOK_NOT_FULLFILL_CANCEL);
                return;
            }

            // Phase2: 真实撮合 — 先撤销需要撤单的订单
            for (MatchOrderV1 needCancelOrder : fokPendingRemoves) {
                needCancelOrder.cancel(fokCancelReasonMap.get(needCancelOrder.getOrderId()));
                orderBook.removeOrder(needCancelOrder);
            }

            // 执行撮合
            fokTryMatchInstantly(newOrder, orderBook);

        } finally {
            fokPendingRemoves.clear();
            fokCancelReasonMap.clear();
        }
    }

    private boolean fokPhase1(MatchOrderV1 newOrder, DepthLineV1 depthLine, OrderBookV1 orderBook,
                              List<MatchOrderV1> fokPendingRemoves,
                              Long2ObjectHashMap<CancelReasonEnum> fokCancelReasonMap) {

        Iterator<MatchOrderV1> orderIterator = depthLine.iterator();
        boolean endMatch = false;

        while (orderIterator.hasNext()) {

            MatchOrderV1 oppoOrder = orderIterator.next();

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

            // 自成交保护
            OpEnum opEnum = stpStrategyService.processSTP(newOrder, oppoOrder, null);
            if (opEnum == OpEnum.OP_BREAK) {
                endMatch = true;
                return endMatch;
            }
            if (opEnum == OpEnum.OP_CONTINUE) {
                fokPendingRemoves.add(oppoOrder);
                fokCancelReasonMap.put(oppoOrder.getOrderId(), CancelReasonEnum.STP_CANCEL);
                continue;
            }

            // 预成交判断 FOK 能否完全成交
            boolean isFokFullFill = isFokFullFill(newOrder, oppoOrder);
            if (isFokFullFill) {
                return endMatch;
            }
        }

        return endMatch;
    }

    private void fokTryMatchInstantly(MatchOrderV1 newOrder, OrderBookV1 orderBook) {

        TreeMap<Long, DepthLineV1> oppositeBook = orderBook.getOppositeBook(newOrder);
        Iterator<Map.Entry<Long, DepthLineV1>> iterator = oppositeBook.entrySet().iterator();

        boolean exit;

        while (iterator.hasNext()) {
            Map.Entry<Long, DepthLineV1> lineEntry = iterator.next();
            long linePrice = lineEntry.getKey();
            DepthLineV1 depthLine = lineEntry.getValue();

            if (depthLine.isEmpty()) {
                log.warn("depthLine is empty. price:{}, symbol:{}", linePrice, newOrder.getSymbolId());
                iterator.remove();
                continue;
            }

            if (!orderBook.isCross(newOrder, linePrice)) {
                break;
            }

            exit = this.fokDoMatch(newOrder, depthLine, orderBook);

            // 如果当前价格档位为空，则移除
            MatchCoreServiceV1.INSTANCE.clearEmptyDepthLine(depthLine, iterator);

            if (exit) {
                break;
            }
        }

        if (newOrder.getOrderStatus() != OrderStatusEnum.FULL_FILLED) {
            log.error("fatal! FOK should full filled but not! status:{}, id:{}",
                    newOrder.getOrderStatus(), newOrder.getOrderId());
        }
    }

    private boolean fokDoMatch(MatchOrderV1 newOrder, DepthLineV1 depthLine, OrderBookV1 orderBook) {

        Iterator<MatchOrderV1> orderIterator = depthLine.iterator();
        boolean endMatch = false;

        while (orderIterator.hasNext()) {
            MatchOrderV1 oppoOrder = orderIterator.next();

            // 成交
            MatchCoreServiceV1.INSTANCE.doMatch(newOrder, oppoOrder, orderIterator, orderBook);

            // 当前订单是否撮合完成
            if (newOrder.isMatchOver()) {
                endMatch = true;
                return endMatch;
            }
        }

        return endMatch;
    }

    /**
     * 模拟成交过程 — 不修改对手单状态
     * 如果 FOK 不能完全成交则后续撤单，否则进入真实成交
     */
    private boolean isFokFullFill(MatchOrderV1 copyOrder, MatchOrderV1 oppoOrder) {

        // 计算成交数量 = min(本单剩余, 对手剩余)
        long matchedQty = Math.min(copyOrder.getRemainingQuantity(), oppoOrder.getRemainingQuantity());

        // 累加 copyOrder 的成交量（不修改真实订单）
        copyOrder.updateFilledQuantity(matchedQty);

        boolean fullFilled = copyOrder.fullFilled();
        if (fullFilled) {
            copyOrder.setOrderStatus(OrderStatusEnum.FULL_FILLED);
            return true;
        }
        return false;
    }
}
