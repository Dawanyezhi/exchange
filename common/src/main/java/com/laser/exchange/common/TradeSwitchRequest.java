package com.laser.exchange.common;

import com.laser.exchange.common.codec.BooleanType;
import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.MessageHeaderEncoder;
import com.laser.exchange.common.codec.TradeSwitchCommandDecoder;
import com.laser.exchange.common.codec.TradeSwitchCommandEncoder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * 币对开关交易请求 (控制面命令，<b>不参与 serialNum 连续性校验</b>)。
 *
 * <p>{@code switchOn=true} 启用交易；{@code false} 禁用。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class TradeSwitchRequest {

    private int symbolCode;
    private boolean switchOn;

    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder header = new MessageHeaderEncoder();
        TradeSwitchCommandEncoder enc = new TradeSwitchCommandEncoder();
        enc.wrapAndApplyHeader(buffer, offset, header);

        enc.symbolCode(symbolCode);
        enc.switchOn(switchOn ? BooleanType.TRUE : BooleanType.FALSE);

        return MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    }

    public TradeSwitchRequest decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        TradeSwitchCommandDecoder dec = new TradeSwitchCommandDecoder();
        dec.wrapAndApplyHeader(buffer, offset, header);

        this.setSymbolCode(dec.symbolCode());
        this.setSwitchOn(dec.switchOn() == BooleanType.TRUE);
        return this;
    }
}
