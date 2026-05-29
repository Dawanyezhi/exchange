package com.laser.exchange.common.result;

import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.SystemErrorCodeEnum;
import com.laser.exchange.common.enums.SystemTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * 撮合结果统一父类。
 *
 * <p>承载所有 MatchResult 共有的"四元组 + 时间戳"头：
 * <ul>
 *   <li>{@link #systemType}: NORMAL/ERROR</li>
 *   <li>{@link #systemErrorCode}: 异常码 (NORMAL 时取 NONE)</li>
 *   <li>{@link #resultBizType}: 业务类型，对应 SBE message 的具体 templateId</li>
 *   <li>{@link #resultSerialNum}: 全局单调自增 (跨 symbol)</li>
 *   <li>{@link #requestSerialNum}: 触发本结果的 request 序号</li>
 *   <li>{@link #createTime}: 由 aeron cluster 共识时钟填充，绝不能用 System.currentTimeMillis</li>
 * </ul>
 *
 * <p>子类由 {@code MatchResultEventsHelper} 在撮合过程中按需创建并编码到 SBE。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public abstract class MatchResult {

    protected SystemTypeEnum systemType;
    protected SystemErrorCodeEnum systemErrorCode;
    protected ResultBizTypeEnum resultBizType;
    protected long resultSerialNum;
    protected long requestSerialNum;
    protected long createTime;

    /**
     * 编码到 SBE 二进制缓冲区。
     *
     * @return 总字节数 (header + body)
     */
    public abstract int encode(MutableDirectBuffer buffer, int offset);

    /**
     * 从 SBE 二进制缓冲区解码到本对象。
     */
    public abstract MatchResult decode(DirectBuffer buffer, int offset);

    protected void decodeHeader(com.laser.exchange.common.codec.ResultCommonHeaderDecoder header) {
        this.systemType = SystemTypeEnum.of((byte) header.systemType());
        this.systemErrorCode = SystemErrorCodeEnum.of((byte) header.systemErrorCode());
        this.resultBizType = ResultBizTypeEnum.of((byte) header.resultBizType());
        this.resultSerialNum = header.resultSerialNum();
        this.requestSerialNum = header.requestSerialNum();
        this.createTime = header.createTime();
    }
}
