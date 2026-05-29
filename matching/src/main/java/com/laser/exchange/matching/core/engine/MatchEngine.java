package com.laser.exchange.matching.core.engine;

import com.alibaba.fastjson.JSON;
import com.laser.exchange.common.config.SymbolConfig;
import com.laser.exchange.matching.core.model.*;
import com.laser.exchange.matching.core.service.FokOrderProcessor;
import com.laser.exchange.matching.core.service.MatchCoreService;
import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.matching.enums.OpEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.matching.core.service.StpStrategyService;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import com.laser.exchange.common.utils.BigDecimalUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * 撮合引擎
 */
@Component
@Slf4j
public class MatchEngine {

    @Getter
    private MatchEngineState matchEngineState = new MatchEngineState();
    private StpStrategyService stpStrategyService = new StpStrategyService();
    private FokOrderProcessor fokOrderProcessor = new FokOrderProcessor();

    /**
     * 撮合结果事件助手：全周期内由 CommandDispatcher 控制生命周期 (beginRequest/endRequest)。
     * MatchEngine 只负责在关键节点 append 事件。
     *
     * <p>字段默认 new 一个实例作为单元测试 fallback（裸 {@code new MatchEngine()} 场景）；
     * Spring 运行时 {@code @Resource} 会反射覆盖为容器管理的 Bean。
     */
    @Resource
    @Getter
    private MatchResultEventsHelper eventsHelper = new MatchResultEventsHelper();

    /**
     * 撮合过程中需要移除的订单列表
     */
    private List<MatchOrder> pendingRemoves = new LinkedList<>();

    /**
     * todo 临时代码，添加配置
     */
    @PostConstruct
    void initEngine() {
        SymbolConfig symbolConfig = new SymbolConfig();
        symbolConfig.setSymbolName("btc-usdt");
        symbolConfig.setSymbolId(1);
        symbolConfig.setSymbolDisplayName(symbolConfig.getSymbolName());
        symbolConfig.setBaseCoinId(1);
        symbolConfig.setQuoteCoinId(2);
        matchEngineState.getMatchContext().addSymbol(1, symbolConfig);

        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setEnabled(true);
        matchConfig.setSymbol(symbolConfig.getSymbolName());
        matchEngineState.addMatchConfig(matchConfig);

        log.info("init symbolConfig :{}", symbolConfig.getSymbolName());
    }

    /**
     * 如果一个订单要交易，确保对应的币对存在
     * 1. 校验是否存在对应的币对配置，如果没有，则直接禁止交易
     * 2. 有币对配置，判断是否开启了交易开关
     * @param matchOrder
     */
    public void placeOrder(MatchOrder matchOrder) {

        String symbolId = matchOrder.getSymbolId();

        if (checkMatchConfig(symbolId)) {
            log.warn("[下单拒绝] symbolId={} 未配置或未启用交易", symbolId);
            return;
        }

        // 获取或者创建对应的订单簿
        MatchContext matchContext = matchEngineState.getMatchContext();

        OrderBook orderBook = matchContext.getOrderBook(symbolId);
        if (orderBook == null) {
            OrderBook newOrderBook = new OrderBook(symbolId);
            matchContext.addOrderBook(newOrderBook);
            orderBook = newOrderBook;
        }

        if (orderBook.isOrderExists(matchOrder.getOrderId())) {
            log.error("placeOrder rejected. order exists! orderId={}", matchOrder.getOrderId());
            matchOrder.reject();
            return;
        }

        // 1) 先生成"挂单成功"事件 (status=NEW)，确保事件序列严格遵循:
        //    PLACE → MATCH(1..N) → CANCEL(可选)
        //    如果先撮合再挂单，会出现 MATCH 在 PLACE 之前的逆序，下游对账无法处理。
        eventsHelper.appendPlaceOrder(matchOrder);

        // 2) 判断订单类型并撮合（撮合期间 match() 内部按需 appendMatch；
        //    POST_ONLY/IOC/FOK 撤单路径在 process*Order 里 appendCancel）
        if (matchOrder.isLimit()) {
            processLimitOrder(matchOrder, orderBook);
        } else {
            processMarketOrder(matchOrder, orderBook);
        }

        // orderBook.printOrderBook("placeOrder");
    }

    /**
     * 撤单
     * @param orderId
     * @param symbolId
     */
    public void cancelOrder(long orderId, String symbolId) {

        if (checkMatchConfig(symbolId)) {
            log.warn("cancelOrder fail. checkMatchConfig error:{}", symbolId);
            return;
        }

        MatchContext matchContext = matchEngineState.getMatchContext();
        OrderBook orderBook = matchContext.getOrderBook(symbolId);
        if (orderBook == null) {
            log.error("cancelOrder fail. No such orderBook. orderId:{}, symbolId:{}", orderId, symbolId);
            return;
        }
        MatchOrder matchOrder = orderBook.getOrder(orderId);
        if (Objects.isNull(matchOrder)) {
            return;
        }

        orderBook.removeOrder(matchOrder);

        // 用户主动撤单：statusOrderStatus 已在 removeOrder 内部置为 CANCELLED（MatchOrder 端），
        // 生成 CancelOrderResult 让下游感知
        matchOrder.cancel(CancelReasonEnum.NONE);
        eventsHelper.appendCancel(matchOrder, CancelReasonEnum.NONE);

        // orderBook.printOrderBook("cancelOrder");
    }

    /**
     * 改单
     * @param orderId
     * @param symbolId
     * @param newDelegatePrice
     * @param newDelegateCount
     */
    public void amendOrder(long orderId, String symbolId, BigDecimal newDelegatePrice, BigDecimal newDelegateCount) {

        if (checkMatchConfig(symbolId)) {
            log.warn("cancelOrder fail. checkMatchConfig error:{}", symbolId);
            return;
        }

        MatchContext matchContext = matchEngineState.getMatchContext();
        OrderBook orderBook = matchContext.getOrderBook(symbolId);

        MatchOrder originalOrder = orderBook.getOrder(orderId);
        if (Objects.isNull(originalOrder)) {
            return;
        }

        MatchOrder amendedOrder = originalOrder.deepCopy();
        if (BigDecimalUtil.greaterThanZero(newDelegatePrice)) {
            amendedOrder.setDelegatePrice(newDelegatePrice);
        }
        if (BigDecimalUtil.greaterThanZero(newDelegateCount)) {
            amendedOrder.setDelegateCount(newDelegateCount);
        }

        orderBook.amendOrder(amendedOrder, originalOrder);

        // orderBook.printOrderBook("amendOrder");
    }

    boolean checkMatchConfig(String symbolId) {
        MatchConfig matchConfig = matchEngineState.getMatchConfig(symbolId);
        if (matchConfig == null) {
            return true;
        }
        return !matchConfig.isEnabled();
    }


    public void processLimitOrder(MatchOrder matchOrder, OrderBook orderBook) {
        TimeInForceEnum timeInForce = matchOrder.getTimeInForce();
        if (timeInForce == null) {
            return;
        }
        switch (timeInForce) {
            case FOK -> processFokOrder(matchOrder, orderBook);
            case GTC -> processGtcOrder(matchOrder, orderBook);
            case POST_ONLY -> processPostOnlyOrder(matchOrder, orderBook);
            case IOC -> processIocOrder(matchOrder, orderBook);
            default ->  log.warn("[限价单处理] 未知的timeInForce类型: {}", timeInForce);
        }
    }

    private void processPostOnlyOrder(MatchOrder matchOrder, OrderBook orderBook) {

        // 如果交叉就撤单
        if (orderBook.isCross(matchOrder)) {
            matchOrder.cancel(CancelReasonEnum.POST_ONLY_CROSS);
            eventsHelper.appendCancel(matchOrder, CancelReasonEnum.POST_ONLY_CROSS);
            return;
        }

        // 不交叉就挂单
        orderBook.addOrder(matchOrder);
    }

    /**
     * 下单
     * 立即执行撮合
     * 如果有剩余就添加到订单簿
     * @param matchOrder
     * @param orderBook
     */
    private void processGtcOrder(MatchOrder matchOrder, OrderBook orderBook) {

        // 判断价格是否交叉，如果没有交叉直接下到深度
        if (gtcNotCross(matchOrder, orderBook)) {
            orderBook.addOrder(matchOrder);
        } else {
            // 价格交叉执行撮合
            tryMatchInstantly(matchOrder, orderBook);

            // 没有完全成交有剩余加到订单簿
            addGtc2BookWhenRemains(matchOrder, orderBook);
        }

    }

    private boolean gtcNotCross(MatchOrder newOrder, OrderBook orderBook) {
        return !orderBook.isCross(newOrder);
    }

    private void addGtc2BookWhenRemains(MatchOrder matchOrder, OrderBook orderBook) {
        if (!matchOrder.isMatchOver()) {
            orderBook.addOrder(matchOrder);
        }
    }

    /**
     * 下单
     * 先撮合
     * 如果能成交，就成交
     * 成交剩余的撤单
     * taker 撤销不在深度
     * @param matchOrder
     * @param orderBook
     */
    private void processIocOrder(MatchOrder matchOrder, OrderBook orderBook) {

        if (orderBook.isCross(matchOrder)) {

            tryMatchInstantly(matchOrder, orderBook);

            if (!matchOrder.fullFilled()) {
                matchOrder.cancel(CancelReasonEnum.IOC_NOT_FULLFILL_CANCEL_REMAINING);
                eventsHelper.appendCancel(matchOrder, CancelReasonEnum.IOC_NOT_FULLFILL_CANCEL_REMAINING);
            }
        } else {
            matchOrder.cancel(CancelReasonEnum.IOC_NOT_CROSS);
            eventsHelper.appendCancel(matchOrder, CancelReasonEnum.IOC_NOT_CROSS);
        }

    }

    /**
     * 撮合核心逻辑：
     * - 判断价格交叉
     * - 交叉才进行撮合
     * - 获取对手订单簿进行遍历
     *   - 根据满足条件的价格，逐层遍历
     *   - 更新对手盘的状态，直到不能成交为止
     * @param newOrder
     * @param orderBook
     */
    void tryMatchInstantly(MatchOrder newOrder, OrderBook orderBook) {

        // 获取对手盘
        TreeMap<BigDecimal, DepthLine> oppositeBook = orderBook.getOppositeBook(newOrder);

        // 迭代对手盘，如果价格交叉就撮合，否则就退出
        // 撮合过程中需要修改深度中的订单
        Iterator<Map.Entry<BigDecimal, DepthLine>> iterator = oppositeBook.entrySet().iterator();

        boolean exit;

        pendingRemoves.clear();

        while (iterator.hasNext()) {
            // 对手盘存在深度档位，判断是否交叉
            Map.Entry<BigDecimal, DepthLine> lineEntry = iterator.next();
            BigDecimal linePrice = lineEntry.getKey();
            DepthLine depthLine = lineEntry.getValue();

            // 深度档位为空
            if (depthLine.isEmpty()) {
                log.warn("depthLine is empty. price:{}, symbol:{}", linePrice.toPlainString(), newOrder.getSymbolId());
                iterator.remove();
                continue;
            }

            // 如果价格不交叉了 停止撮合
            if (!orderBook.isCross(newOrder, linePrice)) {
                break;
            }

            exit = this.match(newOrder, depthLine, orderBook, pendingRemoves);

            // 如果当前价格档位为empty，则移除
            MatchCoreService.INSTANCE.clearEmptyDepthLine(depthLine, iterator);

            if (exit) {
                break;
            }
        }

        // 过程中需要撤销的批量移除订单
        for (MatchOrder order : pendingRemoves) {
            orderBook.removeOrder(order);
        }

        // 触发下一次市价单成交调度（盘口会恢复）
        handleMarketOrderAfterMatching(newOrder, orderBook);

    }

    private void handleMarketOrderAfterMatching(MatchOrder newOrder, OrderBook orderBook) {

        if (newOrder.isMarket()) {

            OrderStatusEnum orderStatus = newOrder.getOrderStatus();

            if (orderStatus.over()) {
                // 移除队列
                orderBook.removeOrder(newOrder);
            } else {
                publishMarketHangingCmd(newOrder);
            }
        }
    }

    /**
     * 遍历价格档位，逐个订单撮合
     *
     * @param newOrder
     * @param depthLine
     * @param pendingRemoves
     * @return true代表跳出循环停止撮合 false代表可以继续撮合
     */
    boolean match(MatchOrder newOrder, DepthLine depthLine, OrderBook orderBook, List<MatchOrder> pendingRemoves) {

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
            OpEnum opEnum = stpStrategyService.processSTP(newOrder, oppoOrder, pendingRemoves);
            if (opEnum == OpEnum.OP_BREAK) {
                // 当前订单撤销，终止撮合
                endMatch = true;
                return endMatch;
            }
            if (opEnum == OpEnum.OP_CONTINUE) {
                // 对手单因为自成交保护撤销，跳过本轮，继续下一轮
                continue;
            }

            // 成交（记录前值以便计算本次成交量）
            BigDecimal takerDealtBefore = newOrder.getDealtCount() != null
                    ? newOrder.getDealtCount() : BigDecimal.ZERO;
            MatchCoreService.INSTANCE.doMatch(newOrder, oppoOrder, orderIterator, orderBook);
            BigDecimal takerDealtAfter = newOrder.getDealtCount() != null
                    ? newOrder.getDealtCount() : BigDecimal.ZERO;
            BigDecimal thisTradeAmount = takerDealtAfter.subtract(takerDealtBefore);

            // 生成撮合结果事件
            if (thisTradeAmount.signum() > 0) {
                eventsHelper.appendMatch(
                        newOrder,
                        oppoOrder,
                        oppoOrder.getDelegatePrice(),      // 撮合价 = maker 价
                        thisTradeAmount,
                        newOrder.getRemainingQuantity(),
                        newOrder.getOrderStatus()
                );
            }

            // 当前订单是否撮合完成
            if (newOrder.isMatchOver()) {
                endMatch = true;
                return endMatch;
            }
        }

        return endMatch;
    }


    /**
     * 两段式撮合过程：
     * - 需要预撮合，遍历订单簿，看能不能完全成交（需要撤单的，暂存撤单列表）
     * - 如果能，那么再发起真正的撮合
     *
     * @param newOrder
     * @param orderBook
     */
    void processFokOrder(MatchOrder newOrder, OrderBook orderBook) {
        fokOrderProcessor.process(newOrder, orderBook);
    }


    /**
     * 市价单一定是taker
     * 一般来说为了让市价单完全成交，会将在途市价单放到一个队列
     * 然后再定时按照队列中市价单添加顺序，将市价单拿出来
     * 再次发送到订单簿中与maker进行成交
     * @param marketOrder
     * @param orderBook
     */
    public void processMarketOrder(MatchOrder marketOrder, OrderBook orderBook) {

        log.debug("[市价单处理] orderId={}, 立即按最优价成交", marketOrder.getOrderId());

        // 判断市价单是否可以直接撮合
        // 市价单也要遵循时间优先策略
        boolean matchInstantly = orderBook.marketOrderMatchInstantly(marketOrder);

        // 市价单先入队列
        orderBook.addOrder(marketOrder);

        if (matchInstantly) {
            // 先进行撮合
            tryMatchInstantly(marketOrder, orderBook);
        } else {
            // 需要发起定时调度任务，触发当前币对买卖市价队列定时撮合的任务（市价单悬挂任务）
            publishMarketHangingCmd(marketOrder);
        }
    }

    /**
     * todo
     * 发送市价单悬挂命令 驱动市价单持续进行成交
     */
    public void publishMarketHangingCmd(MatchOrder marketOrder) {
        log.info("[marketOrder] not first order in the queue Or marketOrder not full_filled Need hanging. marketOrder:{}", JSON.toJSONString(marketOrder, true));
    }

}
