package com.laser.exchange.matching.cluster;

import com.laser.exchange.common.*;
import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.enums.SystemErrorCodeEnum;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.matching.core.engine.MatchEngine;
import com.laser.exchange.matching.core.model.MatchConfig;
import com.laser.exchange.matching.core.model.MatchContext;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.matching.core.service.SymbolService;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import com.laser.exchange.matching.resultLog.ResultLogWriter;
import com.laser.exchange.matching.validation.SerialNumValidator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.agrona.DirectBuffer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

import static com.laser.exchange.matching.enums.CmdType.*;

/**
 * Aeron Cluster 消息分发器。
 *
 * <p>每个请求周期执行统一模板：
 * <pre>
 *   1. 解码 Request
 *   2. serialNum 连续性校验 (不连续 → 生成错误 MatchResult 后丢弃请求)
 *   3. eventsHelper.beginRequest(serialNum, clusterTimestamp)
 *   4. 调用 MatchEngine 业务方法（engine 内部按需 append 事件）
 *   5. eventsHelper.endRequest() → 返回本次 result 列表
 *   6. resultLogWriter.append(results)
 * </pre>
 *
 * <p>所有时间戳均来自 aeron cluster 共识时钟，保证状态机确定性。
 * 下游实时推送和 replay 由独立 result-publisher 服务从 Archive 读取。
 */
@Slf4j
@Component
public class CommandDispatcher {

    @Resource
    private MatchEngine matchEngine;

    @Resource
    private SymbolService symbolService;

    @Resource
    private SerialNumValidator serialNumValidator;

    @Resource
    private MatchResultEventsHelper eventsHelper;

    @Resource
    private ResultLogWriter resultLogWriter;

    /**
     * flyweight 复用，避免热路径上频繁分配对象
     */
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final PlaceOrderRequest placeOrderRequest = new PlaceOrderRequest();
    private final AmendOrderRequest amendOrderRequest = new AmendOrderRequest();
    private final CancelOrderRequest cancelOrderRequest = new CancelOrderRequest();
    private final UpDownSymbolRequest upDownSymbolRequest = new UpDownSymbolRequest();
    private final TradeSwitchRequest tradeSwitchRequest = new TradeSwitchRequest();

    public void dispatchCommand(long timestamp, DirectBuffer buffer, int offset, int length) {

        headerDecoder.wrap(buffer, offset);
        int templateId = headerDecoder.templateId();

        switch (templateId) {
            case PLACE_ORDER_COMMAND -> handlePlace(timestamp, buffer, offset);
            case AMEND_ORDER_COMMAND -> handleAmend(timestamp, buffer, offset);
            case CANCEL_ORDER_COMMAND -> handleCancel(timestamp, buffer, offset);
            case UP_DOWN_SYMBOL_COMMAND -> handleUpDownSymbol(timestamp, buffer, offset);
            case TRADE_SWITCH_COMMAND -> handleTradeSwitch(timestamp, buffer, offset);
            default -> log.error("[CommandDispatcher] unhandled templateId={}", templateId);
        }
    }

    private void handlePlace(long timestamp, DirectBuffer buffer, int offset) {
        placeOrderRequest.decode(buffer, offset);
        log.info("handlePlace serialNum={}, req={}", placeOrderRequest.getSerialNum(), placeOrderRequest);

        if (!serialNumValidator.validateAndAdvance(placeOrderRequest.getSerialNum())) {
            emitSerialNumError(timestamp, placeOrderRequest.getSerialNum());
            return;
        }

        MatchContext matchContext = matchEngine.getMatchEngineState().getMatchContext();
        String symbolId = matchContext.getSymbolNameByCode(placeOrderRequest.getSymbolCode());
        if (Objects.isNull(symbolId)) {
            log.error("No such symbolConfig, {}", placeOrderRequest.getSymbolCode());
            emitOrderReject(timestamp, placeOrderRequest.getSerialNum(), placeOrderRequest.getOrderId(), "",
                    SystemErrorCodeEnum.SYMBOL_NOT_TRADING, "symbol config not found");
            return;
        }
        if (!isTradeEnabled(symbolId)) {
            log.warn("[handlePlace] symbol {} 未开启交易,发拒绝事件", symbolId);
            emitOrderReject(timestamp, placeOrderRequest.getSerialNum(), placeOrderRequest.getOrderId(), symbolId,
                    SystemErrorCodeEnum.SYMBOL_NOT_TRADING, "symbol not trading");
            return;
        }

        MatchOrder matchOrder = new MatchOrder();
        matchOrder.buildNewMatchOrder(timestamp, symbolId, placeOrderRequest);

        eventsHelper.beginRequest(placeOrderRequest.getSerialNum(), timestamp);
        matchEngine.placeOrder(matchOrder);
        flushAndPersist();
    }

    private void handleAmend(long timestamp, DirectBuffer buffer, int offset) {
        amendOrderRequest.decode(buffer, offset);
        log.info("handleAmend serialNum={}, req={}", amendOrderRequest.getSerialNum(), amendOrderRequest);

        if (!serialNumValidator.validateAndAdvance(amendOrderRequest.getSerialNum())) {
            emitSerialNumError(timestamp, amendOrderRequest.getSerialNum());
            return;
        }

        MatchContext matchContext = matchEngine.getMatchEngineState().getMatchContext();
        String symbolId = matchContext.getSymbolNameByCode(amendOrderRequest.getSymbolCode());
        if (Objects.isNull(symbolId)) {
            log.error("No such symbolConfig, {}", amendOrderRequest.getSymbolCode());
            emitOrderReject(timestamp, amendOrderRequest.getSerialNum(), amendOrderRequest.getOrderId(), "",
                    SystemErrorCodeEnum.SYMBOL_NOT_TRADING, "symbol config not found");
            return;
        }
        if (!isTradeEnabled(symbolId)) {
            log.warn("[handleAmend] symbol {} 未开启交易,发拒绝事件", symbolId);
            emitOrderReject(timestamp, amendOrderRequest.getSerialNum(), amendOrderRequest.getOrderId(), symbolId,
                    SystemErrorCodeEnum.SYMBOL_NOT_TRADING, "symbol not trading");
            return;
        }

        eventsHelper.beginRequest(amendOrderRequest.getSerialNum(), timestamp);
        matchEngine.amendOrder(
                amendOrderRequest.getOrderId(),
                symbolId,
                amendOrderRequest.getNewDelegatePrice(),
                amendOrderRequest.getNewDelegateCount()
        );
        flushAndPersist();
    }

    private void handleCancel(long timestamp, DirectBuffer buffer, int offset) {
        cancelOrderRequest.decode(buffer, offset);
        log.info("handleCancel serialNum={}, req={}", cancelOrderRequest.getSerialNum(), cancelOrderRequest);

        if (!serialNumValidator.validateAndAdvance(cancelOrderRequest.getSerialNum())) {
            emitSerialNumError(timestamp, cancelOrderRequest.getSerialNum());
            return;
        }

        MatchContext matchContext = matchEngine.getMatchEngineState().getMatchContext();
        String symbolId = matchContext.getSymbolNameByCode(cancelOrderRequest.getSymbolCode());
        if (Objects.isNull(symbolId)) {
            log.error("No such symbolConfig, {}", cancelOrderRequest.getSymbolCode());
            emitOrderReject(timestamp, cancelOrderRequest.getSerialNum(), cancelOrderRequest.getOrderId(), "",
                    SystemErrorCodeEnum.SYMBOL_NOT_TRADING, "symbol config not found");
            return;
        }
        if (!isTradeEnabled(symbolId)) {
            log.warn("[handleCancel] symbol {} 未开启交易,发拒绝事件", symbolId);
            emitOrderReject(timestamp, cancelOrderRequest.getSerialNum(), cancelOrderRequest.getOrderId(), symbolId,
                    SystemErrorCodeEnum.SYMBOL_NOT_TRADING, "symbol not trading");
            return;
        }

        eventsHelper.beginRequest(cancelOrderRequest.getSerialNum(), timestamp);
        matchEngine.cancelOrder(cancelOrderRequest.getOrderId(), symbolId);
        flushAndPersist();
    }

    /** 检查 symbol 当前是否启用交易 */
    private boolean isTradeEnabled(String symbolId) {
        MatchConfig mc = matchEngine.getMatchEngineState().getMatchConfig(symbolId);
        return mc != null && mc.isEnabled();
    }

    /**
     * 上下币：控制面命令，<b>不参与 serialNum 连续性校验</b>。
     * 时间戳来自 cluster 共识时钟，保证状态机确定性。
     */
    private void handleUpDownSymbol(long timestamp, DirectBuffer buffer, int offset) {
        upDownSymbolRequest.decode(buffer, offset);
        log.info("handleUpDownSymbol op={}, code={}, name={}, base={}, quote={}",
                upDownSymbolRequest.getOp(), upDownSymbolRequest.getSymbolCode(),
                upDownSymbolRequest.getSymbolName(), upDownSymbolRequest.getBaseCoinId(),
                upDownSymbolRequest.getQuoteCoinId());

        // 控制面: requestSerialNum 用 0 标记 (与数据面区分), 共识时间戳保留
        eventsHelper.beginRequest(0L, timestamp);
        switch (upDownSymbolRequest.getOp()) {
            case LIST -> symbolService.listSymbol(
                    upDownSymbolRequest.getSymbolCode(),
                    upDownSymbolRequest.getSymbolName(),
                    upDownSymbolRequest.getBaseCoinId(),
                    upDownSymbolRequest.getQuoteCoinId());
            case DELIST -> symbolService.delistSymbol(upDownSymbolRequest.getSymbolCode());
        }
        flushAndPersist();
    }

    /**
     * 币对开关交易：控制面命令，<b>不参与 serialNum 连续性校验</b>。
     */
    private void handleTradeSwitch(long timestamp, DirectBuffer buffer, int offset) {
        tradeSwitchRequest.decode(buffer, offset);
        log.info("handleTradeSwitch code={}, switchOn={}",
                tradeSwitchRequest.getSymbolCode(), tradeSwitchRequest.isSwitchOn());

        eventsHelper.beginRequest(0L, timestamp);
        symbolService.switchTrade(tradeSwitchRequest.getSymbolCode(), tradeSwitchRequest.isSwitchOn());
        flushAndPersist();
    }

    private void flushAndPersist() {
        List<MatchResult> batch = eventsHelper.endRequest();
        if (!batch.isEmpty()) {
            // 持久化结果
            resultLogWriter.append(batch);
        }
    }

    private void emitSerialNumError(long timestamp, long requestSerialNum) {
        eventsHelper.beginRequest(requestSerialNum, timestamp);
        eventsHelper.appendError(SystemErrorCodeEnum.SERIAL_NUM_NOT_CONTINUOUS, requestSerialNum);
        flushAndPersist();
    }

    private void emitOrderReject(long timestamp, long requestSerialNum, long orderId, String symbolId,
                                 SystemErrorCodeEnum errorCode, String reason) {
        eventsHelper.beginRequest(requestSerialNum, timestamp);
        eventsHelper.appendReject(errorCode, orderId, symbolId, reason);
        flushAndPersist();
    }
}
