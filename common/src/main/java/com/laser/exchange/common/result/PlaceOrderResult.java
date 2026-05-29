package com.laser.exchange.common.result;

import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.MessageHeaderEncoder;
import com.laser.exchange.common.codec.OrderStatus;
import com.laser.exchange.common.codec.PlaceOrderResultDecoder;
import com.laser.exchange.common.codec.PlaceOrderResultEncoder;
import com.laser.exchange.common.enums.MarketTargetTypeEnum;
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
 * 挂单成功结果, resultBizType=PLACE_ORDER, orderStatus=NEW
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class PlaceOrderResult extends MatchResult {

    private long orderId;
    private int symbolCode;
    private OrderStatusEnum orderStatus;
    private String symbolId;
    private BigDecimal delegatePrice;
    private BigDecimal delegateCount;
    private BigDecimal lockedBaseAmount;
    private BigDecimal lockedQuoteAmount;
    private MarketTargetTypeEnum marketTargetType;

    @Override
    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder header = new MessageHeaderEncoder();
        PlaceOrderResultEncoder enc = new PlaceOrderResultEncoder();
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
        enc.lockedBaseAmount(BigDecimalUtil.defaultToString(lockedBaseAmount));
        enc.lockedQuoteAmount(BigDecimalUtil.defaultToString(lockedQuoteAmount));
        enc.marketTargetType(marketTargetType != null ? marketTargetType.name() : "");

        return MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    }

    @Override
    public PlaceOrderResult decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        PlaceOrderResultDecoder dec = new PlaceOrderResultDecoder();
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
        this.lockedBaseAmount = BigDecimalUtil.stringToBigDecimal(dec.lockedBaseAmount());
        this.lockedQuoteAmount = BigDecimalUtil.stringToBigDecimal(dec.lockedQuoteAmount());
        this.marketTargetType = MarketTargetTypeEnum.ofName(dec.marketTargetType());
        return this;
    }
}
