package com.laser.exchange.resultpublisher.archive;

import com.laser.exchange.common.codec.AmendOrderResultDecoder;
import com.laser.exchange.common.codec.CancelOrderResultDecoder;
import com.laser.exchange.common.codec.MatchOrderResultDecoder;
import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.OrderRejectResultDecoder;
import com.laser.exchange.common.codec.PlaceOrderResultDecoder;
import com.laser.exchange.common.codec.TradeSwitchResultDecoder;
import com.laser.exchange.common.codec.UpDownSymbolResultDecoder;
import com.laser.exchange.common.result.AmendOrderResult;
import com.laser.exchange.common.result.CancelOrderResult;
import com.laser.exchange.common.result.MatchOrderResult;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.common.result.OrderRejectResult;
import com.laser.exchange.common.result.PlaceOrderResult;
import com.laser.exchange.common.result.TradeSwitchResult;
import com.laser.exchange.common.result.UpDownSymbolResult;
import com.laser.exchange.resultpublisher.exception.ResultLogReaderException;
import com.laser.exchange.resultpublisher.exception.ResultLogUnknownTemplateException;
import org.agrona.DirectBuffer;

import java.util.Arrays;

public class ResultFrameDecoder {

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    public ResultLogEntry decode(DirectBuffer buffer,
                                 int offset,
                                 int length,
                                 long recordingId,
                                 long startPosition,
                                 long endPosition) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) {
            throw new ResultLogReaderException("result frame too short: length=" + length);
        }

        headerDecoder.wrap(buffer, offset);
        int templateId = headerDecoder.templateId();
        MatchResult result = decodeByTemplateId(templateId, buffer, offset);
        byte[] payload = new byte[length];
        buffer.getBytes(offset, payload);

        return new ResultLogEntry(
                result.getResultSerialNum(),
                recordingId,
                startPosition,
                endPosition,
                templateId,
                result.getRequestSerialNum(),
                result.getCreateTime(),
                payload,
                Arrays.hashCode(payload),
                result
        );
    }

    private MatchResult decodeByTemplateId(int templateId, DirectBuffer buffer, int offset) {
        return switch (templateId) {
            case PlaceOrderResultDecoder.TEMPLATE_ID -> new PlaceOrderResult().decode(buffer, offset);
            case MatchOrderResultDecoder.TEMPLATE_ID -> new MatchOrderResult().decode(buffer, offset);
            case CancelOrderResultDecoder.TEMPLATE_ID -> new CancelOrderResult().decode(buffer, offset);
            case UpDownSymbolResultDecoder.TEMPLATE_ID -> new UpDownSymbolResult().decode(buffer, offset);
            case TradeSwitchResultDecoder.TEMPLATE_ID -> new TradeSwitchResult().decode(buffer, offset);
            case AmendOrderResultDecoder.TEMPLATE_ID -> new AmendOrderResult().decode(buffer, offset);
            case OrderRejectResultDecoder.TEMPLATE_ID -> new OrderRejectResult().decode(buffer, offset);
            default -> throw new ResultLogUnknownTemplateException(templateId);
        };
    }
}
