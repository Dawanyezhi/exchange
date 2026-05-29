package com.laser.exchange.common.result;

import com.laser.exchange.common.codec.CancelOrderResultEncoder;
import com.laser.exchange.common.codec.CancelOrderResultDecoder;
import com.laser.exchange.common.codec.CancelReason;
import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.MessageHeaderEncoder;
import com.laser.exchange.common.codec.OrderStatus;
import com.laser.exchange.common.enums.CancelReasonEnum;
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
 * 撤单结果, resultBizType=CANCEL, orderStatus=CANCELLED
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class CancelOrderResult extends MatchResult {

    private long orderId;
    private int symbolCode;
    private OrderStatusEnum orderStatus;
    private CancelReasonEnum cancelReason;
    private String symbolId;
    private BigDecimal delegatePrice;
    private BigDecimal delegateCount;
    private BigDecimal remainingAmount;

    @Override
    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder header = new MessageHeaderEncoder();
        CancelOrderResultEncoder enc = new CancelOrderResultEncoder();
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
        enc.cancelReason(cancelReason != null
                ? CancelReason.get((short) cancelReason.getCode())
                : CancelReason.NONE);
        enc.symbolId(symbolId != null ? symbolId : "");
        enc.delegatePrice(BigDecimalUtil.defaultToString(delegatePrice));
        enc.delegateCount(BigDecimalUtil.defaultToString(delegateCount));
        enc.remainingAmount(BigDecimalUtil.defaultToString(remainingAmount));

        return MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    }

    @Override
    public CancelOrderResult decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        CancelOrderResultDecoder dec = new CancelOrderResultDecoder();
        dec.wrapAndApplyHeader(buffer, offset, header);

        decodeHeader(dec.header());
        this.orderId = dec.orderId();
        this.symbolCode = dec.symbolCode();

        OrderStatus status = dec.orderStatus();
        this.orderStatus = status != OrderStatus.NULL_VAL
                ? OrderStatusEnum.of(status.value())
                : null;

        CancelReason reason = dec.cancelReason();
        this.cancelReason = reason != CancelReason.NULL_VAL
                ? CancelReasonEnum.of(reason.value())
                : null;

        this.symbolId = dec.symbolId();
        this.delegatePrice = BigDecimalUtil.stringToBigDecimal(dec.delegatePrice());
        this.delegateCount = BigDecimalUtil.stringToBigDecimal(dec.delegateCount());
        this.remainingAmount = BigDecimalUtil.stringToBigDecimal(dec.remainingAmount());
        return this;
    }
}
