package com.laser.exchange.matching.core.engine;

import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.matching.core.model.*;
import com.laser.exchange.matching.core.service.FokOrderProcessorV1;
import com.laser.exchange.matching.core.service.MatchCoreServiceV1;
import com.laser.exchange.matching.core.service.StpStrategyServiceV1;
import com.laser.exchange.matching.enums.OpEnum;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.Object2ObjectHashMap;

import java.util.*;

/**
 * V1 撮合引擎 — 使用 long 替代 BigDecimal，独立于 Spring 容器
 *
 * <p>与 V0 MatchEngine 的核心差异：
 * <ul>
 *   <li>非 Spring @Component，纯 POJO，可直接 new 实例</li>
 *   <li>自持 configMap / orderBookMap 状态（不复用 MatchEngineState / MatchContext）</li>
 *   <li>价格 / 数量全部使用 long，消除 BigDecimal 对象分配与 GC 压力</li>
 *   <li>pendingRemoves 使用 ArrayList 替代 LinkedList</li>
 *   <li>移除所有 printOrderBook 调用（热路径优化）</li>
 *   <li>publishMarketHangingCmd 仅 log.debug，不做 JSON 序列化</li>
 *   <li>修复 V0 amendOrder bug：setDealtCount -> setDelegateCount</li>
 * </ul>
 */
@Slf4j
public class MatchEngineV1 {

    // ======================== 状态管理（自持，不依赖 MatchEngineState）========================

    @Getter
    private final Map<String, MatchConfig> configMap = new Object2ObjectHashMap<>();

    @Getter
    private final Map<String, OrderBookV1> orderBookMap = new Object2ObjectHashMap<>();

    // ======================== 服务依赖 ========================

    private final StpStrategyServiceV1 stpStrategyService = new StpStrategyServiceV1();
    private final FokOrderProcessorV1 fokOrderProcessor = new FokOrderProcessorV1();

    /**
     * 撮合过程中需要移除的订单列表（ArrayList，减少指针追逐开销）
     */
    private final List<MatchOrderV1> pendingRemoves = new ArrayList<>();

    // ======================== 配置管理 ========================

    /**
     * 添加撮合配置（替代 V0 的 @PostConstruct initEngine）
     */
    public void addMatchConfig(MatchConfig config) {
        configMap.put(config.getSymbol(), config);
    }

    /**
     * 初始化默认配置（替代 V0 的 @PostConstruct，按需调用）
     */
    public void initDefaultConfig(String symbolId) {
        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbolId);
        configMap.put(symbolId, matchConfig);
        log.info("V1 initDefaultConfig: {}", symbolId);
    }

    /**
     * 校验撮合配置是否有效
     *
     * @return true 表示配置不存在或未启用（不允许交易）
     */
    boolean checkMatchConfig(String symbolId) {
        MatchConfig matchConfig = configMap.get(symbolId);
        if (matchConfig == null) {
            return true;
        }
        return !matchConfig.isEnabled();
    }

    // ======================== 下单 ========================

    /**
     * 下单入口 — 校验配置后分发到限价 / 市价处理器
     */
    public void placeOrder(MatchOrderV1 matchOrder) {

        String symbolId = matchOrder.getSymbolId();

        if (checkMatchConfig(symbolId)) {
            log.warn("[下单拒绝] symbolId={} 未配置或未启用交易", symbolId);
            return;
        }

        OrderBookV1 orderBook = orderBookMap.get(symbolId);
        if (orderBook == null) {
            OrderBookV1 newOrderBook = new OrderBookV1(symbolId);
            orderBookMap.put(symbolId, newOrderBook);
            orderBook = newOrderBook;
        }

        if (orderBook.isOrderExists(matchOrder.getOrderId())) {
            log.error("placeOrder rejected. order exists! orderId={}", matchOrder.getOrderId());
            matchOrder.reject();
            return;
        }

        if (matchOrder.isLimit()) {
            processLimitOrder(matchOrder, orderBook);
        } else {
            processMarketOrder(matchOrder, orderBook);
        }
    }

    // ======================== 撤单 ========================

    public void cancelOrder(long orderId, String symbolId) {

        if (checkMatchConfig(symbolId)) {
            log.warn("cancelOrder fail. checkMatchConfig error:{}", symbolId);
            return;
        }

        OrderBookV1 orderBook = orderBookMap.get(symbolId);
        if (orderBook == null) {
            log.error("cancelOrder fail. No such orderBook. orderId:{}, symbolId:{}", orderId, symbolId);
            return;
        }

        MatchOrderV1 matchOrder = orderBook.getOrder(orderId);
        if (Objects.isNull(matchOrder)) {
            return;
        }

        orderBook.removeOrder(matchOrder);
    }

    // ======================== 改单（修复 V0 bug：setDealtCount -> setDelegateCount）========================

    /**
     * 改单 — 支持改价、改量、同时改
     *
     * @param orderId           原订单 id
     * @param symbolId          币对
     * @param newDelegatePrice  新委托价格（>0 时生效）
     * @param newDelegateCount  新委托数量（>0 时生效）
     */
    public void amendOrder(long orderId, String symbolId, long newDelegatePrice, long newDelegateCount) {

        if (checkMatchConfig(symbolId)) {
            log.warn("amendOrder fail. checkMatchConfig error:{}", symbolId);
            return;
        }

        OrderBookV1 orderBook = orderBookMap.get(symbolId);
        if (orderBook == null) {
            log.error("amendOrder fail. No such orderBook. orderId:{}, symbolId:{}", orderId, symbolId);
            return;
        }

        MatchOrderV1 originalOrder = orderBook.getOrder(orderId);
        if (Objects.isNull(originalOrder)) {
            return;
        }

        MatchOrderV1 amendedOrder = originalOrder.deepCopy();
        if (newDelegatePrice > 0) {
            amendedOrder.setDelegatePrice(newDelegatePrice);
        }
        if (newDelegateCount > 0) {
            // V0 bug 修复：此处应为 setDelegateCount 而非 setDealtCount
            amendedOrder.setDelegateCount(newDelegateCount);
        }

        orderBook.amendOrder(amendedOrder, originalOrder);
    }

    // ======================== 限价单处理 ========================

    public void processLimitOrder(MatchOrderV1 matchOrder, OrderBookV1 orderBook) {
        TimeInForceEnum timeInForce = matchOrder.getTimeInForce();
        if (timeInForce == null) {
            return;
        }
        switch (timeInForce) {
            case FOK -> processFokOrder(matchOrder, orderBook);
            case GTC -> processGtcOrder(matchOrder, orderBook);
            case POST_ONLY -> processPostOnlyOrder(matchOrder, orderBook);
            case IOC -> processIocOrder(matchOrder, orderBook);
            default -> log.warn("[限价单处理] 未知的timeInForce类型: {}", timeInForce);
        }
    }

    private void processPostOnlyOrder(MatchOrderV1 matchOrder, OrderBookV1 orderBook) {
        // 如果交叉就撤单
        if (orderBook.isCross(matchOrder)) {
            matchOrder.cancel(CancelReasonEnum.POST_ONLY_CROSS);
            return;
        }
        // 不交叉就挂单
        orderBook.addOrder(matchOrder);
    }

    /**
     * GTC 限价单：立即撮合 + 剩余挂单
     */
    private void processGtcOrder(MatchOrderV1 matchOrder, OrderBookV1 orderBook) {
        if (gtcNotCross(matchOrder, orderBook)) {
            orderBook.addOrder(matchOrder);
        } else {
            tryMatchInstantly(matchOrder, orderBook);
            addGtc2BookWhenRemains(matchOrder, orderBook);
        }
    }

    private boolean gtcNotCross(MatchOrderV1 newOrder, OrderBookV1 orderBook) {
        return !orderBook.isCross(newOrder);
    }

    private void addGtc2BookWhenRemains(MatchOrderV1 matchOrder, OrderBookV1 orderBook) {
        if (!matchOrder.isMatchOver()) {
            orderBook.addOrder(matchOrder);
        }
    }

    /**
     * IOC 限价单：立即撮合，未完全成交则撤余量
     */
    private void processIocOrder(MatchOrderV1 matchOrder, OrderBookV1 orderBook) {
        if (orderBook.isCross(matchOrder)) {
            tryMatchInstantly(matchOrder, orderBook);
            if (!matchOrder.fullFilled()) {
                matchOrder.cancel(CancelReasonEnum.IOC_NOT_FULLFILL_CANCEL_REMAINING);
            }
        } else {
            matchOrder.cancel(CancelReasonEnum.IOC_NOT_CROSS);
        }
    }

    /**
     * FOK 限价单：委托给 FokOrderProcessorV1 处理两阶段撮合
     */
    void processFokOrder(MatchOrderV1 newOrder, OrderBookV1 orderBook) {
        fokOrderProcessor.process(newOrder, orderBook);
    }

    // ======================== 撮合核心 ========================

    /**
     * 撮合核心逻辑：遍历对手盘，逐档逐单撮合
     */
    void tryMatchInstantly(MatchOrderV1 newOrder, OrderBookV1 orderBook) {

        TreeMap<Long, DepthLineV1> oppositeBook = orderBook.getOppositeBook(newOrder);
        Iterator<Map.Entry<Long, DepthLineV1>> iterator = oppositeBook.entrySet().iterator();

        boolean exit;
        pendingRemoves.clear();

        while (iterator.hasNext()) {
            Map.Entry<Long, DepthLineV1> lineEntry = iterator.next();
            long linePrice = lineEntry.getKey();
            DepthLineV1 depthLine = lineEntry.getValue();

            // 深度档位为空
            if (depthLine.isEmpty()) {
                log.warn("depthLine is empty. price:{}, symbol:{}", linePrice, newOrder.getSymbolId());
                iterator.remove();
                continue;
            }

            // 价格不交叉则停止撮合
            if (!orderBook.isCross(newOrder, linePrice)) {
                break;
            }

            exit = this.match(newOrder, depthLine, orderBook, pendingRemoves);

            // 清理空档位
            MatchCoreServiceV1.INSTANCE.clearEmptyDepthLine(depthLine, iterator);

            if (exit) {
                break;
            }
        }

        // 批量移除撮合过程中需要撤销的订单
        for (MatchOrderV1 order : pendingRemoves) {
            orderBook.removeOrder(order);
        }

        // 市价单后续调度
        handleMarketOrderAfterMatching(newOrder, orderBook);
    }

    private void handleMarketOrderAfterMatching(MatchOrderV1 newOrder, OrderBookV1 orderBook) {
        if (newOrder.isMarket()) {
            OrderStatusEnum orderStatus = newOrder.getOrderStatus();
            if (orderStatus.over()) {
                orderBook.removeOrder(newOrder);
            } else {
                publishMarketHangingCmd(newOrder);
            }
        }
    }

    /**
     * 遍历价格档位，逐个订单撮合
     *
     * @return true 跳出循环停止撮合；false 可继续撮合
     */
    boolean match(MatchOrderV1 newOrder, DepthLineV1 depthLine, OrderBookV1 orderBook,
                  List<MatchOrderV1> pendingRemoves) {

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
            OpEnum opEnum = stpStrategyService.processSTP(newOrder, oppoOrder, pendingRemoves);
            if (opEnum == OpEnum.OP_BREAK) {
                endMatch = true;
                return endMatch;
            }
            if (opEnum == OpEnum.OP_CONTINUE) {
                continue;
            }

            // 执行成交
            MatchCoreServiceV1.INSTANCE.doMatch(newOrder, oppoOrder, orderIterator, orderBook);

            // 当前订单是否撮合完成
            if (newOrder.isMatchOver()) {
                endMatch = true;
                return endMatch;
            }
        }

        return endMatch;
    }

    // ======================== 市价单处理 ========================

    /**
     * 市价单一定是 taker，入队后根据队列状态决定是否立即撮合
     */
    public void processMarketOrder(MatchOrderV1 marketOrder, OrderBookV1 orderBook) {

        log.debug("[市价单处理] orderId={}, 立即按最优价成交", marketOrder.getOrderId());

        boolean matchInstantly = orderBook.marketOrderMatchInstantly(marketOrder);

        // 市价单先入队列
        orderBook.addOrder(marketOrder);

        if (matchInstantly) {
            tryMatchInstantly(marketOrder, orderBook);
        } else {
            publishMarketHangingCmd(marketOrder);
        }
    }

    /**
     * 市价单悬挂命令 — 仅 debug 日志，不做 JSON 序列化（热路径优化）
     */
    public void publishMarketHangingCmd(MatchOrderV1 marketOrder) {
        log.debug("[marketOrder] not first order in the queue Or marketOrder not full_filled. " +
                "Need hanging. orderId:{}, side:{}, status:{}",
                marketOrder.getOrderId(), marketOrder.getOrderSide(), marketOrder.getOrderStatus());
    }
}
