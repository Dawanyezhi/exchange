package com.laser.exchange.resultpublisher;

import com.laser.exchange.common.enums.MarketTargetTypeEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.SystemErrorCodeEnum;
import com.laser.exchange.common.enums.SystemTypeEnum;
import com.laser.exchange.common.result.PlaceOrderResult;

import java.math.BigDecimal;

public final class ResultFrameFixtures {

    private ResultFrameFixtures() {
    }

    public static PlaceOrderResult placeResult(long resultSerialNum, long requestSerialNum) {
        return PlaceOrderResult.builder()
                .systemType(SystemTypeEnum.NORMAL)
                .systemErrorCode(SystemErrorCodeEnum.NONE)
                .resultBizType(ResultBizTypeEnum.PLACE_ORDER)
                .resultSerialNum(resultSerialNum)
                .requestSerialNum(requestSerialNum)
                .createTime(1_700_000_000_000L + resultSerialNum)
                .orderId(10_000L + resultSerialNum)
                .symbolCode(1)
                .orderStatus(OrderStatusEnum.NEW)
                .symbolId("BTC_USDT")
                .delegatePrice(new BigDecimal("1.23"))
                .delegateCount(new BigDecimal("2.00"))
                .lockedBaseAmount(BigDecimal.ZERO)
                .lockedQuoteAmount(new BigDecimal("2.46"))
                .marketTargetType(MarketTargetTypeEnum.BASE_QTY)
                .build();
    }
}
