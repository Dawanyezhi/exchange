package com.laser.exchange.common.result;

import com.laser.exchange.common.codec.MatchOrderResultEncoder;
import com.laser.exchange.common.codec.MatchOrderResultDecoder;
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
 * 撮合成交结果, resultBizType=MATCH, orderStatus=PARTIALLY_FILLED | FULL_FILLED
 *
 * <p>每一笔成交（taker vs 一个 maker）生成一条独立 result。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class MatchOrderResult extends MatchResult {

    private long orderId;
    /** 对手方订单号 */
    private long oppositeOrderId;
    private int symbolCode;
    private OrderStatusEnum orderStatus;
    private String symbolId;
    private BigDecimal tradePrice;
    private BigDecimal counterTradePrice;
    private BigDecimal tradeAmount;
    private BigDecimal counterTradeAmount;
    private BigDecimal remainingAmount;

    @Override
    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder header = new MessageHeaderEncoder();
        MatchOrderResultEncoder enc = new MatchOrderResultEncoder();
        enc.wrapAndApplyHeader(buffer, offset, header);

        enc.header()
                .systemType(systemType.getCode())
                .systemErrorCode(systemErrorCode.getCode())
                .resultBizType(resultBizType.getCode())
                .resultSerialNum(resultSerialNum)
                .requestSerialNum(requestSerialNum)
                .createTime(createTime);

        enc.orderId(orderId);
        enc.oppositeOrderId(oppositeOrderId);
        enc.symbolCode(symbolCode);
        enc.orderStatus(orderStatus != null
                ? OrderStatus.get((short) orderStatus.getCode())
                : OrderStatus.NULL_VAL);
        enc.symbolId(symbolId != null ? symbolId : "");
        enc.tradePrice(BigDecimalUtil.defaultToString(tradePrice));
        enc.counterTradePrice(BigDecimalUtil.defaultToString(counterTradePrice));
        enc.tradeAmount(BigDecimalUtil.defaultToString(tradeAmount));
        enc.counterTradeAmount(BigDecimalUtil.defaultToString(counterTradeAmount));
        enc.remainingAmount(BigDecimalUtil.defaultToString(remainingAmount));

        return MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    }

    @Override
    public MatchOrderResult decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        MatchOrderResultDecoder dec = new MatchOrderResultDecoder();
        dec.wrapAndApplyHeader(buffer, offset, header);

        decodeHeader(dec.header());
        this.orderId = dec.orderId();
        this.oppositeOrderId = dec.oppositeOrderId();
        this.symbolCode = dec.symbolCode();

        OrderStatus status = dec.orderStatus();
        this.orderStatus = status != OrderStatus.NULL_VAL
                ? OrderStatusEnum.of(status.value())
                : null;

        this.symbolId = dec.symbolId();
        this.tradePrice = BigDecimalUtil.stringToBigDecimal(dec.tradePrice());
        this.counterTradePrice = BigDecimalUtil.stringToBigDecimal(dec.counterTradePrice());
        this.tradeAmount = BigDecimalUtil.stringToBigDecimal(dec.tradeAmount());
        this.counterTradeAmount = BigDecimalUtil.stringToBigDecimal(dec.counterTradeAmount());
        this.remainingAmount = BigDecimalUtil.stringToBigDecimal(dec.remainingAmount());
        return this;
    }
}
