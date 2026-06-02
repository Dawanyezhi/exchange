package com.laser.exchange.matching.core.engine;

import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.common.enums.SystemErrorCodeEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.common.result.AmendOrderResult;
import com.laser.exchange.common.result.CancelOrderResult;
import com.laser.exchange.common.result.MatchOrderResult;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.common.result.OrderRejectResult;
import com.laser.exchange.matching.core.model.MatchConfig;
import com.laser.exchange.matching.core.model.MatchEngineState;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OrderEventCompletenessTest {

    private static final String SYMBOL = "BTC_USDT";

    private MatchEngine engine;
    private MatchResultEventsHelper helper;

    @BeforeEach
    void setUp() {
        resetEngine();
    }

    private void resetEngine() {
        engine = new MatchEngine();
        helper = engine.getEventsHelper();

        MatchEngineState state = engine.getMatchEngineState();
        MatchConfig config = new MatchConfig();
        config.setSymbol(SYMBOL);
        config.setEnabled(true);
        state.addMatchConfig(config);
    }

    private MatchOrder limit(long id, OrderSideEnum side, BigDecimal price, BigDecimal qty, TimeInForceEnum tif) {
        return MatchOrder.builder()
                .orderId(id)
                .symbolId(SYMBOL)
                .accountId(1000L + id)
                .stpAccountId(1000L + id)
                .stpStrategyEnum(StpStrategyEnum.DEFAULT)
                .orderType(OrderType.LIMIT)
                .orderSide(side)
                .timeInForce(tif)
                .delegatePrice(price)
                .delegateCount(qty)
                .dealtCount(BigDecimal.ZERO)
                .orderStatus(OrderStatusEnum.NEW)
                .build();
    }

    private List<MatchResult> place(long requestSerialNum, MatchOrder order) {
        helper.beginRequest(requestSerialNum, requestSerialNum * 100L);
        engine.placeOrder(order);
        return helper.endRequest();
    }

    @Test
    void duplicateOrderEmitsRejectResult() {
        place(1L, limit(1L, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));

        MatchOrder duplicate = limit(1L, OrderSideEnum.SELL, new BigDecimal("99"), BigDecimal.ONE, TimeInForceEnum.GTC);
        List<MatchResult> events = place(2L, duplicate);

        assertEquals(1, events.size());
        OrderRejectResult reject = assertInstanceOf(OrderRejectResult.class, events.get(0));
        assertEquals(ResultBizTypeEnum.ORDER_REJECT, reject.getResultBizType());
        assertEquals(SystemErrorCodeEnum.DUPLICATE_ORDER, reject.getSystemErrorCode());
        assertEquals(OrderStatusEnum.REJECTED, duplicate.getOrderStatus());
    }

    @Test
    void missingCancelAndAmendEmitRejectResult() {
        helper.beginRequest(1L, 100L);
        engine.cancelOrder(404L, SYMBOL);
        List<MatchResult> cancelEvents = helper.endRequest();

        OrderRejectResult cancelReject = assertInstanceOf(OrderRejectResult.class, cancelEvents.get(0));
        assertEquals(SystemErrorCodeEnum.ORDER_NOT_FOUND, cancelReject.getSystemErrorCode());

        helper.beginRequest(2L, 200L);
        engine.amendOrder(404L, SYMBOL, new BigDecimal("101"), BigDecimal.ZERO);
        List<MatchResult> amendEvents = helper.endRequest();

        OrderRejectResult amendReject = assertInstanceOf(OrderRejectResult.class, amendEvents.get(0));
        assertEquals(SystemErrorCodeEnum.ORDER_NOT_FOUND, amendReject.getSystemErrorCode());
    }

    @Test
    void amendSuccessEmitsAmendResult() {
        place(1L, limit(1L, OrderSideEnum.BUY, new BigDecimal("100"), new BigDecimal("2"), TimeInForceEnum.GTC));

        helper.beginRequest(2L, 200L);
        engine.amendOrder(1L, SYMBOL, new BigDecimal("101"), new BigDecimal("3"));
        List<MatchResult> events = helper.endRequest();

        assertEquals(1, events.size());
        AmendOrderResult amend = assertInstanceOf(AmendOrderResult.class, events.get(0));
        assertEquals(ResultBizTypeEnum.AMEND_ORDER, amend.getResultBizType());
        assertEquals(1L, amend.getOrderId());
        assertEquals(new BigDecimal("101"), amend.getDelegatePrice());
        assertEquals(new BigDecimal("3"), amend.getDelegateCount());
        assertEquals(new BigDecimal("3"), amend.getRemainingBaseQty());
    }

    @Test
    void fokFailureAndSuccessEmitTerminalEvents() {
        List<MatchResult> failEvents = place(1L,
                limit(10L, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.FOK));

        assertEquals(2, failEvents.size());
        CancelOrderResult cancel = assertInstanceOf(CancelOrderResult.class, failEvents.get(1));
        assertEquals(CancelReasonEnum.FOK_NOT_CROSS, cancel.getCancelReason());

        place(2L, limit(20L, OrderSideEnum.SELL, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC));
        List<MatchResult> successEvents = place(3L,
                limit(21L, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.FOK));

        assertEquals(2, successEvents.size());
        MatchOrderResult match = assertInstanceOf(MatchOrderResult.class, successEvents.get(1));
        assertEquals(ResultBizTypeEnum.MATCH, match.getResultBizType());
        assertEquals(OrderStatusEnum.FULL_FILLED, match.getTakerOrderStatus());
        assertEquals(OrderStatusEnum.FULL_FILLED, match.getMakerOrderStatus());
    }

    @Test
    void stpCancelTakerMakerAndBothEmitCancelResults() {
        place(1L, stpOrder(1L, OrderSideEnum.SELL, StpStrategyEnum.DEFAULT, 7L));
        List<MatchResult> cancelTakerEvents = place(2L,
                stpOrder(2L, OrderSideEnum.BUY, StpStrategyEnum.CANCEL_TAKER, 7L));
        assertEquals(ResultBizTypeEnum.CANCEL, cancelTakerEvents.get(1).getResultBizType());
        assertEquals(2L, ((CancelOrderResult) cancelTakerEvents.get(1)).getOrderId());

        resetEngine();
        place(3L, stpOrder(3L, OrderSideEnum.SELL, StpStrategyEnum.DEFAULT, 8L));
        List<MatchResult> cancelMakerEvents = place(4L,
                stpOrder(4L, OrderSideEnum.BUY, StpStrategyEnum.CANCEL_MAKER, 8L));
        assertEquals(ResultBizTypeEnum.CANCEL, cancelMakerEvents.get(1).getResultBizType());
        assertEquals(3L, ((CancelOrderResult) cancelMakerEvents.get(1)).getOrderId());

        resetEngine();
        place(5L, stpOrder(5L, OrderSideEnum.SELL, StpStrategyEnum.DEFAULT, 9L));
        List<MatchResult> cancelBothEvents = place(6L,
                stpOrder(6L, OrderSideEnum.BUY, StpStrategyEnum.CANCEL_BOTH, 9L));
        assertEquals(3, cancelBothEvents.size());
        assertEquals(6L, ((CancelOrderResult) cancelBothEvents.get(1)).getOrderId());
        assertEquals(5L, ((CancelOrderResult) cancelBothEvents.get(2)).getOrderId());
    }

    private MatchOrder stpOrder(long id, OrderSideEnum side, StpStrategyEnum strategy, long stpAccountId) {
        MatchOrder order = limit(id, side, new BigDecimal("100"), BigDecimal.ONE, TimeInForceEnum.GTC);
        order.setStpStrategyEnum(strategy);
        order.setStpAccountId(stpAccountId);
        order.setAccountId(stpAccountId);
        return order;
    }
}
