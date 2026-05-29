package com.laser.exchange.common;

import com.laser.exchange.common.codec.CancelOrderCommandDecoder;
import com.laser.exchange.common.codec.CancelOrderCommandEncoder;
import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.MessageHeaderEncoder;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

@Data
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class CancelOrderRequest extends AbstractRequest {

    /**
     * 要撤单的撮合/柜台 内部订单号
     */
    private long orderId;

    /**
     * 要撤单的币对
     */
    private int symbolCode;

    /**
     * 将 CancelOrderRequest 编码为 SBE 二进制格式
     *
     * @param buffer  目标缓冲区
     * @param offset  写入偏移量
     * @return 编码后的总字节数（header + body）
     */
    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        CancelOrderCommandEncoder encoder = new CancelOrderCommandEncoder();
        encoder.wrapAndApplyHeader(buffer, offset, headerEncoder);

        encoder.serialNum(this.getSerialNum());
        encoder.orderId(this.getOrderId());
        encoder.symbolCode(this.getSymbolCode());

        return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
    }

    /**
     * 从 SBE 二进制缓冲区解码为 CancelOrderRequest 对象
     *
     * @param buffer 源缓冲区
     * @param offset 读取偏移量
     * @return 解码后的 CancelOrderRequest
     */
    public CancelOrderRequest decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
        CancelOrderCommandDecoder decoder = new CancelOrderCommandDecoder();
        decoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        this.setSerialNum(decoder.serialNum());
        this.setOrderId(decoder.orderId());
        this.setSymbolCode(decoder.symbolCode());

        return this;
    }
}
