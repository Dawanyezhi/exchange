package com.laser.exchange.common;

import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.MessageHeaderEncoder;
import com.laser.exchange.common.codec.SymbolOp;
import com.laser.exchange.common.codec.UpDownSymbolCommandDecoder;
import com.laser.exchange.common.codec.UpDownSymbolCommandEncoder;
import com.laser.exchange.common.enums.SymbolOpEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * 上下币请求 (控制面命令，<b>不参与 serialNum 连续性校验</b>)。
 *
 * <p>因此本类<b>不继承 AbstractRequest</b>，与下单/改单/撤单的数据面命令在颗粒度上区分。
 *
 * <p>属性：
 * <ul>
 *   <li>{@link #op}: LIST=上币 / DELIST=下币</li>
 *   <li>{@link #symbolCode}: 全局唯一 symbol 编号</li>
 *   <li>{@link #symbolName}: 如 "doge-usdt"</li>
 *   <li>{@link #baseCoinId} / {@link #quoteCoinId}: 基础币 / 计价币</li>
 * </ul>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@ToString
public class UpDownSymbolRequest {

    private SymbolOpEnum op;
    private int symbolCode;
    private long baseCoinId;
    private long quoteCoinId;
    private String symbolName;

    public int encode(MutableDirectBuffer buffer, int offset) {
        MessageHeaderEncoder header = new MessageHeaderEncoder();
        UpDownSymbolCommandEncoder enc = new UpDownSymbolCommandEncoder();
        enc.wrapAndApplyHeader(buffer, offset, header);

        enc.op(op != null ? SymbolOp.get((short) op.getCode()) : SymbolOp.NULL_VAL);
        enc.symbolCode(symbolCode);
        enc.baseCoinId((int) baseCoinId);
        enc.quoteCoinId((int) quoteCoinId);
        enc.symbolName(symbolName != null ? symbolName : "");

        return MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength();
    }

    public UpDownSymbolRequest decode(DirectBuffer buffer, int offset) {
        MessageHeaderDecoder header = new MessageHeaderDecoder();
        UpDownSymbolCommandDecoder dec = new UpDownSymbolCommandDecoder();
        dec.wrapAndApplyHeader(buffer, offset, header);

        SymbolOp sbeOp = dec.op();
        this.setOp(sbeOp != SymbolOp.NULL_VAL ? SymbolOpEnum.of((byte) sbeOp.value()) : null);
        this.setSymbolCode(dec.symbolCode());
        this.setBaseCoinId(dec.baseCoinId());
        this.setQuoteCoinId(dec.quoteCoinId());
        this.setSymbolName(dec.symbolName());
        return this;
    }
}
