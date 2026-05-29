package com.laser.exchange.common;

import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.common.enums.MarketTargetTypeEnum;
import org.agrona.ExpandableArrayBuffer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaceOrderRequestTest {

    @Test
    void encodeDecodeCarriesLockedQuoteAmount() {
        PlaceOrderRequest request = PlaceOrderRequest.builder()
                .orderId(1001L)
                .clientOid("oid-1001")
                .accountId(2001L)
                .symbolCode(1)
                .orderType(OrderType.MARKET)
                .orderSide(OrderSideEnum.BUY)
                .timeInForce(TimeInForceEnum.GTC)
                .delegatePrice(BigDecimal.ZERO)
                .delegateCount(new BigDecimal("1.25"))
                .lockedQuoteAmount(new BigDecimal("62500.50"))
                .targetQuoteAmount(new BigDecimal("0"))
                .lockedBaseAmount(new BigDecimal("0"))
                .marketTargetType(MarketTargetTypeEnum.BASE_QTY)
                .stpAccountId(2001L)
                .stpStrategyEnum(StpStrategyEnum.DEFAULT)
                .build();

        ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(512);
        request.encode(buffer, 0);

        PlaceOrderRequest decoded = new PlaceOrderRequest().decode(buffer, 0);

        assertEquals(request.getOrderId(), decoded.getOrderId());
        assertEquals(request.getOrderType(), decoded.getOrderType());
        assertEquals(request.getOrderSide(), decoded.getOrderSide());
        assertEquals(new BigDecimal("1.25"), decoded.getDelegateCount());
        assertEquals(new BigDecimal("62500.5"), decoded.getLockedQuoteAmount());
        assertEquals(BigDecimal.ZERO, decoded.getTargetQuoteAmount());
        assertEquals(BigDecimal.ZERO, decoded.getLockedBaseAmount());
        assertEquals(MarketTargetTypeEnum.BASE_QTY, decoded.getMarketTargetType());
    }

    @Test
    void encodeDecodeCarriesQuoteAmountMarketFields() {
        PlaceOrderRequest request = PlaceOrderRequest.builder()
                .orderId(1002L)
                .clientOid("oid-1002")
                .accountId(2002L)
                .symbolCode(1)
                .orderType(OrderType.MARKET)
                .orderSide(OrderSideEnum.SELL)
                .timeInForce(TimeInForceEnum.GTC)
                .delegatePrice(BigDecimal.ZERO)
                .delegateCount(BigDecimal.ZERO)
                .targetQuoteAmount(new BigDecimal("100.25"))
                .lockedBaseAmount(new BigDecimal("0.01"))
                .lockedQuoteAmount(BigDecimal.ZERO)
                .marketTargetType(MarketTargetTypeEnum.QUOTE_AMOUNT)
                .stpAccountId(2002L)
                .stpStrategyEnum(StpStrategyEnum.DEFAULT)
                .build();

        ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(512);
        request.encode(buffer, 0);

        PlaceOrderRequest decoded = new PlaceOrderRequest().decode(buffer, 0);

        assertEquals(new BigDecimal("100.25"), decoded.getTargetQuoteAmount());
        assertEquals(new BigDecimal("0.01"), decoded.getLockedBaseAmount());
        assertEquals(MarketTargetTypeEnum.QUOTE_AMOUNT, decoded.getMarketTargetType());
    }
}
