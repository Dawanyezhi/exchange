package com.laser.exchange.matching.result;

import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.common.enums.MarketTargetTypeEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.SymbolOpEnum;
import com.laser.exchange.common.enums.SystemErrorCodeEnum;
import com.laser.exchange.common.enums.SystemTypeEnum;
import com.laser.exchange.common.result.CancelOrderResult;
import com.laser.exchange.common.result.MatchOrderResult;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.common.result.PlaceOrderResult;
import com.laser.exchange.common.result.TradeSwitchResult;
import com.laser.exchange.common.result.UpDownSymbolResult;
import com.laser.exchange.matching.core.model.MatchOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MatchResult 事件累积助手 (灵感来自 exchange-core OrderBookEventsHelper)。
 *
 * <p><b>使用模式</b>：撮合状态机单线程执行，每个 request 周期：
 * <pre>
 *   helper.beginRequest(req.getSerialNum(), cluster.time());   // 入口
 *     ... 业务逻辑调用 helper.appendXxx(...) 多次
 *   List&lt;MatchResult&gt; batch = helper.endRequest();              // 出口
 *   resultRepository.persist(batch);
 * </pre>
 *
 * <p><b>确定性约束</b>：
 * <ul>
 *   <li>{@code resultSerialNum} 用普通 long 严格 +1，禁用 AtomicLong</li>
 *   <li>{@code createTime} 来自 aeron cluster 共识时间戳，禁用 {@code System.currentTimeMillis()}</li>
 *   <li>本类不持有锁、不暴露并发原语；同一时刻仅状态机线程访问</li>
 * </ul>
 *
 * <p><b>快照恢复</b>：调用 {@link #restoreNextResultSerialNum(long)} 直接覆写下一个分配号。
 */
@Slf4j
@Component
public class MatchResultEventsHelper {

    /** 当前 request 的累积桶。endRequest 返回后清空。 */
    private final List<MatchResult> currentBatch = new ArrayList<>(16);

    /** 下一个待分配的 resultSerialNum，从 1 开始。 */
    private long nextResultSerialNum = 1L;

    /** 当前 request 的序号，由 beginRequest 注入。 */
    private long currentRequestSerialNum = 0L;

    /** 当前 request 的共识时间戳。 */
    private long currentTimestamp = 0L;

    private boolean inRequest = false;

    /**
     * 标记一个 request 处理周期开始。
     *
     * @param requestSerialNum 来自 AbstractRequest.serialNum
     * @param consensusTimestamp aeron cluster 共识时间戳 (毫秒)
     */
    public void beginRequest(long requestSerialNum, long consensusTimestamp) {
        if (inRequest) {
            log.warn("[EventsHelper] beginRequest called while previous batch unflushed, batch.size={}, prevReqSerialNum={}", currentBatch.size(), currentRequestSerialNum);
            currentBatch.clear();
        }
        currentRequestSerialNum = requestSerialNum;
        currentTimestamp = consensusTimestamp;
        inRequest = true;
    }

    /**
     * 关闭 request 处理周期，返回有序 result 列表（按 resultSerialNum 升序）。
     *
     * @return 不可变快照；helper 内部 buffer 已清空
     */
    public List<MatchResult> endRequest() {
        if (!inRequest) {
            return Collections.emptyList();
        }
        List<MatchResult> snapshot = new ArrayList<>(currentBatch);
        currentBatch.clear();
        inRequest = false;
        return snapshot;
    }

    // ============ append APIs ============

    public PlaceOrderResult appendPlaceOrder(MatchOrder order) {
        PlaceOrderResult result = PlaceOrderResult.builder()
                .systemType(SystemTypeEnum.NORMAL)
                .systemErrorCode(SystemErrorCodeEnum.NONE)
                .resultBizType(ResultBizTypeEnum.PLACE_ORDER)
                .resultSerialNum(allocSerialNum())
                .requestSerialNum(currentRequestSerialNum)
                .createTime(currentTimestamp)
                .orderId(order.getOrderId())
                .symbolCode(0)  // TODO MatchOrder 当前无 symbolCode 字段，Sprint2 整合时回填
                .symbolId(order.getSymbolId())
                .orderStatus(OrderStatusEnum.NEW)
                .delegatePrice(order.getDelegatePrice())
                .delegateCount(order.getDelegateCount())
                .lockedBaseAmount(order.getLockedBaseAmount())
                .lockedQuoteAmount(order.getLockedQuoteAmount())
                .marketTargetType(order.getEffectiveMarketTargetType())
                .build();
        currentBatch.add(result);
        return result;
    }

    public MatchOrderResult appendMatch(MatchOrder taker, MatchOrder maker,
                                        BigDecimal tradePrice, BigDecimal tradeAmount,
                                        BigDecimal takerRemaining, OrderStatusEnum takerStatus) {
        BigDecimal tradeQuoteAmount = tradePrice.multiply(tradeAmount);
        MatchOrderResult result = MatchOrderResult.builder()
                .systemType(SystemTypeEnum.NORMAL)
                .systemErrorCode(SystemErrorCodeEnum.NONE)
                .resultBizType(ResultBizTypeEnum.MATCH)
                .resultSerialNum(allocSerialNum())
                .requestSerialNum(currentRequestSerialNum)
                .createTime(currentTimestamp)
                .orderId(taker.getOrderId())
                .oppositeOrderId(maker.getOrderId())
                .symbolCode(0)
                .symbolId(taker.getSymbolId())
                .orderStatus(takerStatus)
                .tradePrice(tradePrice)
                .counterTradePrice(maker.getDelegatePrice())
                .tradeAmount(tradeAmount)
                .counterTradeAmount(tradeAmount) // 双边数量相等
                .remainingAmount(resolveLegacyRemainingAmount(taker, takerRemaining))
                .tradeBaseQty(tradeAmount)
                .tradeQuoteAmount(tradeQuoteAmount)
                .remainingBaseQty(resolveRemainingBaseQty(taker))
                .remainingQuoteAmount(resolveRemainingQuoteAmount(taker))
                .build();
        currentBatch.add(result);
        return result;
    }

    public CancelOrderResult appendCancel(MatchOrder order, CancelReasonEnum reason) {
        CancelOrderResult result = CancelOrderResult.builder()
                .systemType(SystemTypeEnum.NORMAL)
                .systemErrorCode(SystemErrorCodeEnum.NONE)
                .resultBizType(ResultBizTypeEnum.CANCEL)
                .resultSerialNum(allocSerialNum())
                .requestSerialNum(currentRequestSerialNum)
                .createTime(currentTimestamp)
                .orderId(order.getOrderId())
                .symbolCode(0)
                .symbolId(order.getSymbolId())
                .orderStatus(OrderStatusEnum.CANCELLED)
                .cancelReason(reason)
                .delegatePrice(order.getDelegatePrice())
                .delegateCount(order.getDelegateCount())
                .remainingAmount(resolveRemainingBaseQty(order))
                .remainingBaseQty(resolveRemainingBaseQty(order))
                .remainingQuoteAmount(resolveRemainingQuoteAmount(order))
                .usedQuoteAmount(order.getUsedQuoteAmount())
                .build();
        currentBatch.add(result);
        return result;
    }

    public UpDownSymbolResult appendUpDownSymbol(SymbolOpEnum op, int symbolCode, String symbolName,
                                                 long baseCoinId, long quoteCoinId) {
        // 控制面命令不参与 serialNum 校验，但仍生成事件序列；如果 helper 未 beginRequest，
        // 也允许独立生成 (撮合状态机内部调用，sliding 时机由 dispatcher 控制)
        UpDownSymbolResult result = UpDownSymbolResult.builder()
                .systemType(SystemTypeEnum.NORMAL)
                .systemErrorCode(SystemErrorCodeEnum.NONE)
                .resultBizType(op == SymbolOpEnum.LIST
                        ? ResultBizTypeEnum.SYMBOL_UP : ResultBizTypeEnum.SYMBOL_DOWN)
                .resultSerialNum(allocSerialNum())
                .requestSerialNum(currentRequestSerialNum)
                .createTime(currentTimestamp)
                .op(op)
                .symbolCode(symbolCode)
                .symbolName(symbolName)
                .baseCoinId(baseCoinId)
                .quoteCoinId(quoteCoinId)
                .build();
        currentBatch.add(result);
        return result;
    }

    public TradeSwitchResult appendTradeSwitch(int symbolCode, String symbolId, boolean switchOn) {
        TradeSwitchResult result = TradeSwitchResult.builder()
                .systemType(SystemTypeEnum.NORMAL)
                .systemErrorCode(SystemErrorCodeEnum.NONE)
                .resultBizType(ResultBizTypeEnum.TRADE_SWITCH)
                .resultSerialNum(allocSerialNum())
                .requestSerialNum(currentRequestSerialNum)
                .createTime(currentTimestamp)
                .symbolCode(symbolCode)
                .symbolId(symbolId)
                .switchOn(switchOn)
                .build();
        currentBatch.add(result);
        return result;
    }

    /**
     * 生成"系统级错误"结果。常见用法：serialNum 不连续、币对未上线等。
     *
     * <p>通过复用 {@link PlaceOrderResult} 形态承载错误，让下游用统一通道消费。
     * 错误结果不携带订单业务字段（orderId 可传 0）。
     */
    public PlaceOrderResult appendError(SystemErrorCodeEnum errorCode, long requestSerialNum) {
        PlaceOrderResult result = PlaceOrderResult.builder()
                .systemType(SystemTypeEnum.ERROR)
                .systemErrorCode(errorCode)
                .resultBizType(ResultBizTypeEnum.PLACE_ORDER)
                .resultSerialNum(allocSerialNum())
                .requestSerialNum(requestSerialNum)
                .createTime(currentTimestamp)
                .orderId(0L)
                .symbolCode(0)
                .symbolId("")
                .orderStatus(OrderStatusEnum.REJECTED)
                .delegatePrice(BigDecimal.ZERO)
                .delegateCount(BigDecimal.ZERO)
                .lockedBaseAmount(BigDecimal.ZERO)
                .lockedQuoteAmount(BigDecimal.ZERO)
                .marketTargetType(MarketTargetTypeEnum.BASE_QTY)
                .build();
        currentBatch.add(result);
        return result;
    }

    private BigDecimal resolveRemainingBaseQty(MatchOrder order) {
        if (order == null) {
            return BigDecimal.ZERO;
        }
        if (order.isMarketTargetQuoteAmount() && order.isBuy()) {
            return BigDecimal.ZERO;
        }
        if (order.isMarketTargetQuoteAmount() && order.isSell()) {
            return order.getRemainingBaseBudget();
        }
        return order.getRemainingQuantity();
    }

    private BigDecimal resolveLegacyRemainingAmount(MatchOrder order, BigDecimal takerRemaining) {
        if (order != null && order.isMarketTargetQuoteAmount()) {
            return resolveRemainingBaseQty(order);
        }
        return takerRemaining != null ? takerRemaining : BigDecimal.ZERO;
    }

    private BigDecimal resolveRemainingQuoteAmount(MatchOrder order) {
        if (order == null || !order.isMarket()) {
            return BigDecimal.ZERO;
        }
        if (order.isBuy()) {
            return order.getRemainingQuoteBudget();
        }
        if (order.isMarketTargetQuoteAmount()) {
            return order.getRemainingQuoteTarget();
        }
        return BigDecimal.ZERO;
    }

    private long allocSerialNum() {
        return nextResultSerialNum++;
    }

    public long getNextResultSerialNum() {
        return nextResultSerialNum;
    }

    /**
     * 快照恢复入口：直接覆写下一个待分配号。
     *
     * @param next 下一个 resultSerialNum (= 已用最大值 + 1)
     */
    public void restoreNextResultSerialNum(long next) {
        log.info("[EventsHelper] restore nextResultSerialNum from {} to {}", nextResultSerialNum, next);
        nextResultSerialNum = next;
    }

    /** 仅供测试 */
    public void resetForTest() {
        currentBatch.clear();
        nextResultSerialNum = 1L;
        currentRequestSerialNum = 0L;
        currentTimestamp = 0L;
        inRequest = false;
    }

    public boolean isInRequest() {
        return inRequest;
    }

    public int currentBatchSize() {
        return currentBatch.size();
    }
}
