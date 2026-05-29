package com.laser.exchange.common;

import com.laser.exchange.common.codec.*;
import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.common.utils.BigDecimalUtil;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class PlaceOrderRequest extends AbstractRequest {

    /**
     * 订单号 使用idGenerator生成，long型，雪花算法
     * 为了方便实现时间优先
     */
    private long orderId;

    /**
     * 客户端订单号，用户推送场景，或者查询场景，增加客户端端置信度
     */
    private String clientOid;

    /**
     * 用户编码
     */
    private long accountId;

    /**
     * 标的编码
     */
    private int symbolCode;

    /**
     * 订单类型
     */
    private OrderType orderType;

    /**
     * 买卖方向
     */
    private OrderSideEnum orderSide;

    /**
     * 生效类型
     */
    private TimeInForceEnum timeInForce;

    /**
     * 委托价格
     */
    private BigDecimal delegatePrice;

    /**
     * 委托数量
     */
    private BigDecimal delegateCount;

    /**
     * 自成交保护的账户id（挂单maker的stpAccount和taker的stpAccount如果相等，就代表可能发生自成交）
     * 确保在子账号下单的场景下，有一个唯一的用户编码能够进行关联（eg：母账号和子账号之间也需要进行自成交保护，那么确保母子账号使用相同的stpAccountId，一般来说用母账户的accountId作为stpAccountId）
     */
    private long stpAccountId;

    /**
     * 自成交保护策略
     */
    private StpStrategyEnum stpStrategyEnum;

    /**
     * 将 PlaceOrderRequest 编码为 SBE 二进制格式
     *
     * @param buffer  目标缓冲区
     * @param offset  写入偏移量
     * @return 编码后的总字节数（header + body）
     */
    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        PlaceOrderCommandEncoder encoder = new PlaceOrderCommandEncoder();
        encoder.wrapAndApplyHeader(buffer, offset, headerEncoder);

        // 序号在最前
        encoder.serialNum(this.getSerialNum());

        // 定长 field
        encoder.orderId(this.getOrderId());
        encoder.accountId(this.getAccountId());
        encoder.symbolCode(this.getSymbolCode());
        encoder.orderType(this.getOrderType() != null
                ? com.laser.exchange.common.codec.OrderType.get((short) this.getOrderType().getCode())
                : com.laser.exchange.common.codec.OrderType.NULL_VAL);
        encoder.orderSide(this.getOrderSide() != null
                ? OrderSide.get((short) this.getOrderSide().getCode())
                : OrderSide.NULL_VAL);
        encoder.timeInForce(this.getTimeInForce() != null
                ? TimeInForce.get((short) this.getTimeInForce().getCode())
                : TimeInForce.NULL_VAL);
        encoder.stpAccountId(this.getStpAccountId());
        encoder.stpStrategy(this.getStpStrategyEnum() != null
                ? StpStrategy.get((short) this.getStpStrategyEnum().getCode())
                : StpStrategy.NULL_VAL);

        // 变长 data（必须按 schema 定义顺序写入）
        encoder.delegatePrice(BigDecimalUtil.defaultToString(this.getDelegatePrice()));
        encoder.delegateCount(BigDecimalUtil.defaultToString(this.getDelegateCount()));
        encoder.clientOid(this.getClientOid() != null ? this.getClientOid() : "");

        return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
    }

    /**
     * 从 SBE 二进制缓冲区解码为 PlaceOrderRequest 对象
     *
     * @param buffer 源缓冲区
     * @param offset 读取偏移量
     * @return 解码后的 PlaceOrderRequest
     */
    public PlaceOrderRequest decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        PlaceOrderCommandDecoder decoder = new PlaceOrderCommandDecoder();
        decoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        // 序号在最前
        this.setSerialNum(decoder.serialNum());

        // 定长 field
        this.setOrderId(decoder.orderId());
        this.setAccountId(decoder.accountId());
        this.setSymbolCode(decoder.symbolCode());

        com.laser.exchange.common.codec.OrderType sbeOrderType = decoder.orderType();
        this.setOrderType(sbeOrderType != com.laser.exchange.common.codec.OrderType.NULL_VAL
                ? OrderType.of(sbeOrderType.value()) : null);

        OrderSide sbeOrderSide = decoder.orderSide();
        this.setOrderSide(sbeOrderSide != OrderSide.NULL_VAL
                ? OrderSideEnum.of(sbeOrderSide.value()) : null);

        TimeInForce sbeTimeInForce = decoder.timeInForce();
        this.setTimeInForce(sbeTimeInForce != TimeInForce.NULL_VAL
                ? TimeInForceEnum.of(sbeTimeInForce.value()) : null);

        this.setStpAccountId(decoder.stpAccountId());

        StpStrategy sbeStpStrategy = decoder.stpStrategy();
        this.setStpStrategyEnum(sbeStpStrategy != StpStrategy.NULL_VAL
                ? StpStrategyEnum.of(sbeStpStrategy.value()) : null);

        // 变长 data（必须按 schema 定义顺序读取）
        String priceStr = decoder.delegatePrice();
        this.setDelegatePrice(BigDecimalUtil.stringToBigDecimal(priceStr));

        String countStr = decoder.delegateCount();
        this.setDelegateCount(BigDecimalUtil.stringToBigDecimal(countStr));

        this.setClientOid(decoder.clientOid());

        return this;
    }
}
