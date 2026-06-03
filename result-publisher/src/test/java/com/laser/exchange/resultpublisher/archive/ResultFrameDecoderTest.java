package com.laser.exchange.resultpublisher.archive;

import com.laser.exchange.common.codec.PlaceOrderResultDecoder;
import com.laser.exchange.common.enums.MarketTargetTypeEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.SystemErrorCodeEnum;
import com.laser.exchange.common.enums.SystemTypeEnum;
import com.laser.exchange.common.result.PlaceOrderResult;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ResultFrameDecoderTest {

    private final ResultFrameDecoder decoder = new ResultFrameDecoder();

    @Test
    @DisplayName("解码 PlaceOrderResult 并保留原始 payload 与索引字段")
    void decodePlaceOrderResult() {
        EncodedFrame frame = encode(placeResult(1L, 1001L, "1.23"));

        ResultLogEntry entry = decoder.decode(
                frame.buffer(),
                0,
                frame.length(),
                7L,
                128L,
                256L
        );

        assertEquals(1L, entry.resultSerialNum());
        assertEquals(7L, entry.recordingId());
        assertEquals(128L, entry.startPosition());
        assertEquals(256L, entry.endPosition());
        assertEquals(PlaceOrderResultDecoder.TEMPLATE_ID, entry.templateId());
        assertEquals(1001L, entry.requestSerialNum());
        assertEquals(1_700_000_000_001L, entry.createTime());
        assertEquals(frame.length(), entry.payload().length);
        assertInstanceOf(PlaceOrderResult.class, entry.result());
    }

    static EncodedFrame encode(PlaceOrderResult result) {
        ExpandableArrayBuffer encodeBuffer = new ExpandableArrayBuffer(512);
        int length = result.encode(encodeBuffer, 0);
        byte[] payload = new byte[length];
        encodeBuffer.getBytes(0, payload);
        return new EncodedFrame(new UnsafeBuffer(payload), length);
    }

    static PlaceOrderResult placeResult(long resultSerialNum, long requestSerialNum, String delegatePrice) {
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
                .delegatePrice(new BigDecimal(delegatePrice))
                .delegateCount(new BigDecimal("2.00"))
                .lockedBaseAmount(BigDecimal.ZERO)
                .lockedQuoteAmount(new BigDecimal("2.46"))
                .marketTargetType(MarketTargetTypeEnum.BASE_QTY)
                .build();
    }

    static final class EncodedFrame {

        private final UnsafeBuffer buffer;
        private final int length;

        private EncodedFrame(UnsafeBuffer buffer, int length) {
            this.buffer = buffer;
            this.length = length;
        }

        UnsafeBuffer buffer() {
            return buffer;
        }

        int length() {
            return length;
        }
    }
}
