package com.laser.exchange.matching.result;

import com.laser.exchange.common.enums.CancelReasonEnum;
import com.laser.exchange.common.enums.MarketTargetTypeEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.SystemErrorCodeEnum;
import com.laser.exchange.common.enums.SystemTypeEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.common.result.CancelOrderResult;
import com.laser.exchange.common.result.AmendOrderResult;
import com.laser.exchange.common.result.MatchOrderResult;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.common.result.OrderRejectResult;
import com.laser.exchange.common.result.PlaceOrderResult;
import com.laser.exchange.common.result.TradeSwitchResult;
import com.laser.exchange.common.result.UpDownSymbolResult;
import com.laser.exchange.matching.core.model.MatchOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchResultEventsHelperTest {

    private MatchResultEventsHelper helper;

    @BeforeEach
    void setUp() {
        helper = new MatchResultEventsHelper();
    }

    private MatchOrder makeOrder(long orderId, String symbol, BigDecimal price, BigDecimal qty) {
        return MatchOrder.builder()
                .orderId(orderId)
                .symbolId(symbol)
                .orderType(OrderType.LIMIT)
                .orderSide(OrderSideEnum.BUY)
                .timeInForce(TimeInForceEnum.GTC)
                .delegatePrice(price)
                .delegateCount(qty)
                .dealtCount(BigDecimal.ZERO)
                .orderStatus(OrderStatusEnum.NEW)
                .build();
    }

    @Test
    void resultSerialNumStartsAtOneAndIncrements() {
        helper.beginRequest(101L, 1700000000000L);

        MatchOrder o1 = makeOrder(1L, "BTC_USDT", new BigDecimal("100"), new BigDecimal("1"));
        MatchOrder o2 = makeOrder(2L, "BTC_USDT", new BigDecimal("101"), new BigDecimal("2"));

        PlaceOrderResult r1 = helper.appendPlaceOrder(o1);
        PlaceOrderResult r2 = helper.appendPlaceOrder(o2);

        assertEquals(1L, r1.getResultSerialNum());
        assertEquals(2L, r2.getResultSerialNum());
        assertEquals(101L, r1.getRequestSerialNum());
        assertEquals(101L, r2.getRequestSerialNum());
    }

    @Test
    void timestampComesFromBeginRequestNotSystemTime() {
        long fixedTime = 1234567890L;
        helper.beginRequest(1L, fixedTime);

        PlaceOrderResult r = helper.appendPlaceOrder(
                makeOrder(1L, "ETH_USDT", new BigDecimal("3000"), BigDecimal.ONE));

        assertEquals(fixedTime, r.getCreateTime(),
                "createTime 必须等于 beginRequest 注入的共识时间戳，禁用 System.currentTimeMillis");
    }

    @Test
    void serialNumIsGlobalAcrossSymbolsAndRequests() {
        helper.beginRequest(1L, 100L);
        PlaceOrderResult a = helper.appendPlaceOrder(makeOrder(1, "BTC_USDT", BigDecimal.TEN, BigDecimal.ONE));
        helper.endRequest();

        helper.beginRequest(2L, 200L);
        PlaceOrderResult b = helper.appendPlaceOrder(makeOrder(2, "ETH_USDT", BigDecimal.TEN, BigDecimal.ONE));
        helper.endRequest();

        helper.beginRequest(3L, 300L);
        PlaceOrderResult c = helper.appendPlaceOrder(makeOrder(3, "BTC_USDT", BigDecimal.TEN, BigDecimal.ONE));
        helper.endRequest();

        assertEquals(1L, a.getResultSerialNum());
        assertEquals(2L, b.getResultSerialNum());
        assertEquals(3L, c.getResultSerialNum(),
                "resultSerialNum 必须跨 symbol、跨 request 全局单调");
    }

    @Test
    void endRequestReturnsBatchAndClears() {
        helper.beginRequest(7L, 500L);
        helper.appendPlaceOrder(makeOrder(1, "BTC_USDT", BigDecimal.ONE, BigDecimal.ONE));
        helper.appendPlaceOrder(makeOrder(2, "BTC_USDT", BigDecimal.ONE, BigDecimal.ONE));

        assertEquals(2, helper.currentBatchSize());

        List<MatchResult> batch = helper.endRequest();

        assertEquals(2, batch.size());
        assertEquals(0, helper.currentBatchSize());
        assertFalse(helper.isInRequest());
    }

    @Test
    void appendMatchProducesMatchOrderResult() {
        helper.beginRequest(5L, 999L);

        MatchOrder taker = makeOrder(100, "BTC_USDT", new BigDecimal("50000"), new BigDecimal("2"));
        MatchOrder maker = makeOrder(200, "BTC_USDT", new BigDecimal("49999"), new BigDecimal("3"));

        MatchOrderResult result = helper.appendMatch(taker, maker,
                new BigDecimal("49999"), new BigDecimal("2"),
                OrderStatusEnum.FULL_FILLED);

        assertEquals(ResultBizTypeEnum.MATCH, result.getResultBizType());
        assertEquals(result.getResultSerialNum(), result.getTradeId());
        assertEquals(100L, result.getTakerOrderId());
        assertEquals(200L, result.getMakerOrderId());
        assertEquals(new BigDecimal("49999"), result.getTradePrice());
        assertEquals(new BigDecimal("2"), result.getTradeBaseQty());
        assertEquals(new BigDecimal("99998"), result.getTradeQuoteAmount());
        assertEquals(OrderStatusEnum.FULL_FILLED, result.getTakerOrderStatus());
        assertEquals(OrderStatusEnum.NEW, result.getMakerOrderStatus());
    }

    @Test
    void appendMatchCarriesMarketQuoteRemainders() {
        helper.beginRequest(6L, 1000L);

        MatchOrder taker = MatchOrder.builder()
                .orderId(101L)
                .symbolId("BTC_USDT")
                .orderType(OrderType.MARKET)
                .orderSide(OrderSideEnum.BUY)
                .timeInForce(TimeInForceEnum.GTC)
                .delegatePrice(BigDecimal.ZERO)
                .delegateCount(BigDecimal.ZERO)
                .lockedQuoteAmount(new BigDecimal("100"))
                .usedQuoteAmount(new BigDecimal("60"))
                .marketTargetType(MarketTargetTypeEnum.QUOTE_AMOUNT)
                .dealtCount(new BigDecimal("0.002"))
                .orderStatus(OrderStatusEnum.PARTIALLY_FILLED)
                .build();
        MatchOrder maker = makeOrder(201L, "BTC_USDT", new BigDecimal("30000"), new BigDecimal("1"));

        MatchOrderResult result = helper.appendMatch(taker, maker,
                new BigDecimal("30000"), new BigDecimal("0.002"),
                OrderStatusEnum.PARTIALLY_FILLED);

        assertEquals(new BigDecimal("0.002"), result.getTradeBaseQty());
        assertEquals(new BigDecimal("60.000"), result.getTradeQuoteAmount());
        assertEquals(BigDecimal.ZERO, result.getTakerRemainingBaseQty());
        assertEquals(new BigDecimal("40"), result.getTakerRemainingQuoteAmount());
    }

    @Test
    void appendCancelProducesCancelOrderResult() {
        helper.beginRequest(8L, 1000L);

        MatchOrder o = makeOrder(50, "ETH_USDT", new BigDecimal("3000"), new BigDecimal("5"));

        CancelOrderResult result = helper.appendCancel(o, CancelReasonEnum.STP_CANCEL);

        assertEquals(ResultBizTypeEnum.CANCEL, result.getResultBizType());
        assertEquals(OrderStatusEnum.CANCELLED, result.getOrderStatus());
        assertEquals(CancelReasonEnum.STP_CANCEL, result.getCancelReason());
        assertEquals(new BigDecimal("5"), result.getRemainingBaseQty());
        assertEquals(BigDecimal.ZERO, result.getRemainingQuoteAmount());
    }

    @Test
    void appendUpDownSymbolBranchesByFlag() {
        helper.beginRequest(10L, 2000L);

        UpDownSymbolResult up = helper.appendUpDownSymbol(
                com.laser.exchange.common.enums.SymbolOpEnum.LIST, 100, "BTC_USDT", 1, 2);
        UpDownSymbolResult down = helper.appendUpDownSymbol(
                com.laser.exchange.common.enums.SymbolOpEnum.DELIST, 100, "BTC_USDT", 1, 2);

        assertEquals(ResultBizTypeEnum.SYMBOL_UP, up.getResultBizType());
        assertEquals(ResultBizTypeEnum.SYMBOL_DOWN, down.getResultBizType());
    }

    @Test
    void appendTradeSwitchCarriesFlag() {
        helper.beginRequest(11L, 3000L);

        TradeSwitchResult on = helper.appendTradeSwitch(100, "BTC_USDT", true);
        TradeSwitchResult off = helper.appendTradeSwitch(100, "BTC_USDT", false);

        assertEquals(ResultBizTypeEnum.TRADE_SWITCH, on.getResultBizType());
        assertTrue(on.isSwitchOn());
        assertFalse(off.isSwitchOn());
    }

    @Test
    void appendErrorMarksSystemError() {
        helper.beginRequest(0L, 0L);  // error path 时不一定有 valid request 上下文

        OrderRejectResult err = helper.appendError(SystemErrorCodeEnum.SERIAL_NUM_NOT_CONTINUOUS, 999L);

        assertEquals(SystemTypeEnum.ERROR, err.getSystemType());
        assertEquals(SystemErrorCodeEnum.SERIAL_NUM_NOT_CONTINUOUS, err.getSystemErrorCode());
        assertEquals(999L, err.getRequestSerialNum());
        assertEquals(OrderStatusEnum.REJECTED, err.getOrderStatus());
        assertEquals(ResultBizTypeEnum.ORDER_REJECT, err.getResultBizType());
    }

    @Test
    void appendAmendProducesAmendOrderResult() {
        helper.beginRequest(12L, 4000L);

        MatchOrder order = makeOrder(60, "BTC_USDT", new BigDecimal("10"), new BigDecimal("5"));
        order.updateFilledQuantity(new BigDecimal("2"));

        AmendOrderResult result = helper.appendAmend(order);

        assertEquals(ResultBizTypeEnum.AMEND_ORDER, result.getResultBizType());
        assertEquals(60L, result.getOrderId());
        assertEquals(OrderStatusEnum.PARTIALLY_FILLED, result.getOrderStatus());
        assertEquals(new BigDecimal("3"), result.getRemainingBaseQty());
    }

    @Test
    void restoreNextResultSerialNumOverwrites() {
        helper.beginRequest(1L, 1L);
        helper.appendPlaceOrder(makeOrder(1, "BTC_USDT", BigDecimal.ONE, BigDecimal.ONE));
        helper.endRequest();

        helper.restoreNextResultSerialNum(100L);

        helper.beginRequest(2L, 2L);
        PlaceOrderResult r = helper.appendPlaceOrder(makeOrder(2, "BTC_USDT", BigDecimal.ONE, BigDecimal.ONE));

        assertEquals(100L, r.getResultSerialNum());
        assertEquals(101L, helper.getNextResultSerialNum());
    }
}
