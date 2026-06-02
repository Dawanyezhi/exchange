package com.laser.exchange.common.result;

import com.laser.exchange.common.codec.AmendOrderResultDecoder;
import com.laser.exchange.common.codec.AmendOrderResultEncoder;
import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.MessageHeaderEncoder;
import com.laser.exchange.common.codec.OrderStatus;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.utils.BigDecimalUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.math.BigDecimal;

/**
 * 改单结果, resultBizType=AMEND_ORDER。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class AmendOrderResult extends MatchResult {

    private long orderId;
    private int symbolCode;
    private OrderStatusEnum orderStatus;
    private String symbolId;
    private BigDecimal delegatePrice;
    private BigDecimal delegateCount;
    private BigDecimal remainingAmount;
    private BigDecimal remainingBaseQty;
    private BigDecimal remainingQuoteAmount;

    @Override
    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder header = new MessageHeaderEncoder();
        AmendOrderResultEncoder enc = new AmendOrderResultEncoder();
        enc.wrapAndApplyHeader(buffer, offset, header);

        enc.header()
                .systemType(systemType.getCode())
                .systemErrorCode(systemErrorCode.getCode())
                .resultBizType(resultBizType.getCode())
                .resultSerialNum(resultSerialNum)
                .requestSerialNum(requestSerialNum)
                .createTime(createTime);

        enc.orderId(orderId);
        enc.symbolCode(symbolCode);
        enc.orderStatus(orderStatus != null
                ? OrderStatus.get((short) orderStatus.getCode())
                : OrderStatus.NULL_VAL);
        enc.symbolId(symbolId != null ? symbolId : "");
        enc.delegatePrice(BigDecimalUtil.defaultToString(delegatePrice));
        enc.delegateCount(BigDecimalUtil.defaultToString(delegateCount));
        enc.remainingAmount(BigDecimalUtil.defaultToString(remainingAmount));
        enc.remainingBaseQty(BigDecimalUtil.defaultToString(remainingBaseQty));
        enc.remainingQuoteAmount(BigDecimalUtil.defaultToString(remainingQuoteAmount));

        return MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    }

    @Override
    public AmendOrderResult decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        AmendOrderResultDecoder dec = new AmendOrderResultDecoder();
        dec.wrapAndApplyHeader(buffer, offset, header);

        decodeHeader(dec.header());
        this.orderId = dec.orderId();
        this.symbolCode = dec.symbolCode();

        OrderStatus status = dec.orderStatus();
        this.orderStatus = status != OrderStatus.NULL_VAL
                ? OrderStatusEnum.of(status.value())
                : null;

        this.symbolId = dec.symbolId();
        this.delegatePrice = BigDecimalUtil.stringToBigDecimal(dec.delegatePrice());
        this.delegateCount = BigDecimalUtil.stringToBigDecimal(dec.delegateCount());
        this.remainingAmount = BigDecimalUtil.stringToBigDecimal(dec.remainingAmount());
        this.remainingBaseQty = BigDecimalUtil.stringToBigDecimal(dec.remainingBaseQty());
        this.remainingQuoteAmount = BigDecimalUtil.stringToBigDecimal(dec.remainingQuoteAmount());
        return this;
    }
}
