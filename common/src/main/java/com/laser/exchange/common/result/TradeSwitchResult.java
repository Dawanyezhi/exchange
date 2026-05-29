package com.laser.exchange.common.result;

import com.laser.exchange.common.codec.BooleanType;
import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.MessageHeaderEncoder;
import com.laser.exchange.common.codec.TradeSwitchResultDecoder;
import com.laser.exchange.common.codec.TradeSwitchResultEncoder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * 币对开关结果, resultBizType=TRADE_SWITCH
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class TradeSwitchResult extends MatchResult {

    private int symbolCode;
    /** true 开启, false 关闭 */
    private boolean switchOn;
    private String symbolId;

    @Override
    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder header = new MessageHeaderEncoder();
        TradeSwitchResultEncoder enc = new TradeSwitchResultEncoder();
        enc.wrapAndApplyHeader(buffer, offset, header);

        enc.header()
                .systemType(systemType.getCode())
                .systemErrorCode(systemErrorCode.getCode())
                .resultBizType(resultBizType.getCode())
                .resultSerialNum(resultSerialNum)
                .requestSerialNum(requestSerialNum)
                .createTime(createTime);

        enc.symbolCode(symbolCode);
        enc.switchOn(switchOn ? BooleanType.TRUE : BooleanType.FALSE);
        enc.symbolId(symbolId != null ? symbolId : "");

        return MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    }

    @Override
    public TradeSwitchResult decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        TradeSwitchResultDecoder dec = new TradeSwitchResultDecoder();
        dec.wrapAndApplyHeader(buffer, offset, header);

        decodeHeader(dec.header());
        this.symbolCode = dec.symbolCode();
        this.switchOn = dec.switchOn() == BooleanType.TRUE;
        this.symbolId = dec.symbolId();
        return this;
    }
}
