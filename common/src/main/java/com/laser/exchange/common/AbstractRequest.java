package com.laser.exchange.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * 撮合请求统一父类。
 *
 * <p>持有 {@code serialNum} 用于服务端在入口处做严格 +1 连续性校验，
 * 用于检测客户端丢包或 raftlog 回放完整性。
 *
 * <p>子类必须在 encode/decode 时通过 SBE encoder/decoder 写入/读取 serialNum 字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public abstract class AbstractRequest {

    /**
     * 请求序号，由客户端分配，全局严格单调递增 (newSerialNum = oldSerialNum + 1)。
     * 服务端 {@code SerialNumValidator} 会校验连续性。
     */
    protected long serialNum;

    /**
     * 编码到 SBE 二进制缓冲区。
     *
     * @return 总字节数 (header + body)
     */
    public abstract int encode(MutableDirectBuffer buffer, int offset);

    /**
     * 从 SBE 二进制缓冲区解码到本对象。
     */
    public abstract AbstractRequest decode(DirectBuffer buffer, int offset);
}