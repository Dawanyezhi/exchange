package com.laser.exchange.common.result;

import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.MessageHeaderEncoder;
import com.laser.exchange.common.codec.OrderRejectResultDecoder;
import com.laser.exchange.common.codec.OrderRejectResultEncoder;
import com.laser.exchange.common.codec.OrderStatus;
import com.laser.exchange.common.enums.OrderStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * 订单请求拒绝结果, resultBizType=ORDER_REJECT。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class OrderRejectResult extends MatchResult {

    private long orderId;
    private int symbolCode;
    private OrderStatusEnum orderStatus;
    private String symbolId;
    private String rejectReason;

    @Override
    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder header = new MessageHeaderEncoder();
        OrderRejectResultEncoder enc = new OrderRejectResultEncoder();
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
        enc.rejectReason(rejectReason != null ? rejectReason : "");

        return MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    }

    @Override
    public OrderRejectResult decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        OrderRejectResultDecoder dec = new OrderRejectResultDecoder();
        dec.wrapAndApplyHeader(buffer, offset, header);

        decodeHeader(dec.header());
        this.orderId = dec.orderId();
        this.symbolCode = dec.symbolCode();

        OrderStatus status = dec.orderStatus();
        this.orderStatus = status != OrderStatus.NULL_VAL
                ? OrderStatusEnum.of(status.value())
                : null;

        this.symbolId = dec.symbolId();
        this.rejectReason = dec.rejectReason();
        return this;
    }
}
