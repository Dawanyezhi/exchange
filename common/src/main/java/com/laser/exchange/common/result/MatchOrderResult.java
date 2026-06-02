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

    /** 成交唯一编号。当前阶段使用 resultSerialNum 作为 tradeId。 */
    private long tradeId;

    /** 主动吃单方订单号。 */
    private long takerOrderId;

    /** 被动挂单方订单号。 */
    private long makerOrderId;

    /** 币对数字编码，来自 symbol 配置；当前撮合模型尚未回填时为 0。 */
    private int symbolCode;

    /** 本笔成交后 taker 订单状态。 */
    private OrderStatusEnum takerOrderStatus;

    /** 本笔成交后 maker 订单状态。 */
    private OrderStatusEnum makerOrderStatus;

    /** 币对标识，例如 BTC_USDT。 */
    private String symbolId;

    /** 本笔成交价格。按 maker 价格成交。 */
    private BigDecimal tradePrice;

    /** 本笔成交基础币数量。 */
    private BigDecimal tradeBaseQty;

    /** 本笔成交计价币金额，等于 tradePrice * tradeBaseQty。 */
    private BigDecimal tradeQuoteAmount;

    /** 本笔成交后 taker 剩余基础币数量；按 quote 金额买入的市价单为 0。 */
    private BigDecimal takerRemainingBaseQty;

    /** 本笔成交后 taker 剩余计价币预算或目标；不适用时为 0。 */
    private BigDecimal takerRemainingQuoteAmount;

    /** 本笔成交后 maker 剩余基础币数量。 */
    private BigDecimal makerRemainingBaseQty;

    /** 本笔成交后 maker 剩余计价币金额；限价 maker 当前为 0。 */
    private BigDecimal makerRemainingQuoteAmount;

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

        enc.tradeId(tradeId);
        enc.takerOrderId(takerOrderId);
        enc.makerOrderId(makerOrderId);
        enc.symbolCode(symbolCode);
        enc.takerOrderStatus(takerOrderStatus != null
                ? OrderStatus.get((short) takerOrderStatus.getCode())
                : OrderStatus.NULL_VAL);
        enc.makerOrderStatus(makerOrderStatus != null
                ? OrderStatus.get((short) makerOrderStatus.getCode())
                : OrderStatus.NULL_VAL);
        enc.symbolId(symbolId != null ? symbolId : "");
        enc.tradePrice(BigDecimalUtil.defaultToString(tradePrice));
        enc.tradeBaseQty(BigDecimalUtil.defaultToString(tradeBaseQty));
        enc.tradeQuoteAmount(BigDecimalUtil.defaultToString(tradeQuoteAmount));
        enc.takerRemainingBaseQty(BigDecimalUtil.defaultToString(takerRemainingBaseQty));
        enc.takerRemainingQuoteAmount(BigDecimalUtil.defaultToString(takerRemainingQuoteAmount));
        enc.makerRemainingBaseQty(BigDecimalUtil.defaultToString(makerRemainingBaseQty));
        enc.makerRemainingQuoteAmount(BigDecimalUtil.defaultToString(makerRemainingQuoteAmount));

        return MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    }

    @Override
    public MatchOrderResult decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        MatchOrderResultDecoder dec = new MatchOrderResultDecoder();
        dec.wrapAndApplyHeader(buffer, offset, header);

        decodeHeader(dec.header());
        this.tradeId = dec.tradeId();
        this.takerOrderId = dec.takerOrderId();
        this.makerOrderId = dec.makerOrderId();
        this.symbolCode = dec.symbolCode();

        OrderStatus takerStatus = dec.takerOrderStatus();
        this.takerOrderStatus = takerStatus != OrderStatus.NULL_VAL
                ? OrderStatusEnum.of(takerStatus.value())
                : null;

        OrderStatus makerStatus = dec.makerOrderStatus();
        this.makerOrderStatus = makerStatus != OrderStatus.NULL_VAL
                ? OrderStatusEnum.of(makerStatus.value())
                : null;

        this.symbolId = dec.symbolId();
        this.tradePrice = BigDecimalUtil.stringToBigDecimal(dec.tradePrice());
        this.tradeBaseQty = BigDecimalUtil.stringToBigDecimal(dec.tradeBaseQty());
        this.tradeQuoteAmount = BigDecimalUtil.stringToBigDecimal(dec.tradeQuoteAmount());
        this.takerRemainingBaseQty = BigDecimalUtil.stringToBigDecimal(dec.takerRemainingBaseQty());
        this.takerRemainingQuoteAmount = BigDecimalUtil.stringToBigDecimal(dec.takerRemainingQuoteAmount());
        this.makerRemainingBaseQty = BigDecimalUtil.stringToBigDecimal(dec.makerRemainingBaseQty());
        this.makerRemainingQuoteAmount = BigDecimalUtil.stringToBigDecimal(dec.makerRemainingQuoteAmount());
        return this;
    }
}
