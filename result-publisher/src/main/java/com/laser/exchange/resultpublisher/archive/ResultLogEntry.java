package com.laser.exchange.resultpublisher.archive;

import com.laser.exchange.common.result.MatchResult;

import java.util.Arrays;
import java.util.Objects;

/**
 * 一条从 result-data-stream replay 出来的撮合结果事件。
 *
 */
public final class ResultLogEntry {

    private final long resultSerialNum;
    private final long recordingId;
    private final long startPosition;
    private final long endPosition;
    private final int templateId;
    private final long requestSerialNum;
    private final long createTime;
    private final byte[] payload;
    private final int payloadHash;
    private final MatchResult result;

    public ResultLogEntry(long resultSerialNum,
                          long recordingId,
                          long startPosition,
                          long endPosition,
                          int templateId,
                          long requestSerialNum,
                          long createTime,
                          byte[] payload,
                          int payloadHash,
                          MatchResult result) {
        this.resultSerialNum = resultSerialNum;
        this.recordingId = recordingId;
        this.startPosition = startPosition;
        this.endPosition = endPosition;
        this.templateId = templateId;
        this.requestSerialNum = requestSerialNum;
        this.createTime = createTime;
        this.payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        this.payloadHash = payloadHash;
        this.result = Objects.requireNonNull(result, "result");
    }

    public long resultSerialNum() {
        return resultSerialNum;
    }

    public long recordingId() {
        return recordingId;
    }

    public long startPosition() {
        return startPosition;
    }

    public long endPosition() {
        return endPosition;
    }

    public int templateId() {
        return templateId;
    }

    public long requestSerialNum() {
        return requestSerialNum;
    }

    public long createTime() {
        return createTime;
    }

    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public int payloadHash() {
        return payloadHash;
    }

    public MatchResult result() {
        return result;
    }

    boolean samePayload(ResultLogEntry other) {
        return templateId == other.templateId
                && requestSerialNum == other.requestSerialNum
                && payloadHash == other.payloadHash
                && Arrays.equals(payload, other.payload);
    }
}
