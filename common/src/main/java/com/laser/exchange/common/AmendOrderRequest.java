package com.laser.exchange.common;

import com.laser.exchange.common.codec.AmendOrderCommandDecoder;
import com.laser.exchange.common.codec.AmendOrderCommandEncoder;
import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.MessageHeaderEncoder;
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
public class AmendOrderRequest extends AbstractRequest {

    /**
     * 待修改订单号
     */
    private long orderId;

    /**
     * 待修改订单币对
     */
    private int symbolCode;

    /**
     * 新委托价格
     */
    private BigDecimal newDelegatePrice;

    /**
     * 新委托数量
     */
    private BigDecimal newDelegateCount;

    /**
     * 将 AmendOrderRequest 编码为 SBE 二进制格式
     *
     * @param buffer  目标缓冲区
     * @param offset  写入偏移量
     * @return 编码后的总字节数（header + body）
     */
    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        AmendOrderCommandEncoder encoder = new AmendOrderCommandEncoder();
        encoder.wrapAndApplyHeader(buffer, offset, headerEncoder);

        // 序号在最前
        encoder.serialNum(this.getSerialNum());

        // 定长 field
        encoder.orderId(this.getOrderId());
        encoder.symbolCode(this.getSymbolCode());

        // 变长 data（必须按 schema 定义顺序写入）
        encoder.newDelegatePrice(BigDecimalUtil.defaultToString(this.getNewDelegatePrice()));
        encoder.newDelegateCount(BigDecimalUtil.defaultToString(this.getNewDelegateCount()));

        return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
    }

    /**
     * 从 SBE 二进制缓冲区解码为 AmendOrderRequest 对象
     *
     * @param buffer 源缓冲区
     * @param offset 读取偏移量
     * @return 解码后的 AmendOrderRequest
     */
    public AmendOrderRequest decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        AmendOrderCommandDecoder decoder = new AmendOrderCommandDecoder();
        decoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        // 序号在最前
        this.setSerialNum(decoder.serialNum());

        // 定长 field
        this.setOrderId(decoder.orderId());
        this.setSymbolCode(decoder.symbolCode());

        // 变长 data（必须按 schema 定义顺序读取）
        String priceStr = decoder.newDelegatePrice();
        this.setNewDelegatePrice(BigDecimalUtil.stringToBigDecimal(priceStr));

        String countStr = decoder.newDelegateCount();
        this.setNewDelegateCount(BigDecimalUtil.stringToBigDecimal(countStr));

        return this;
    }
}
