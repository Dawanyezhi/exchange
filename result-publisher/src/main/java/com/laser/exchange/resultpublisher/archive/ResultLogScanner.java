package com.laser.exchange.resultpublisher.archive;

import com.laser.exchange.resultpublisher.exception.ResultLogGapException;
import com.laser.exchange.resultpublisher.exception.ResultLogPayloadMismatchException;

public class ResultLogScanner {

    private final ResultLogScanState state;
    private final ResultLogEntryHandler handler;
    private final DuplicateResultVerifier duplicateVerifier;

    public ResultLogScanner(ResultLogEntryHandler handler) {
        this(new ResultLogScanState(), handler, DuplicateResultVerifier.lastAcceptedOnly());
    }

    public ResultLogScanner(ResultLogScanState state, ResultLogEntryHandler handler) {
        this(state, handler, DuplicateResultVerifier.lastAcceptedOnly());
    }

    public ResultLogScanner(ResultLogScanState state,
                            ResultLogEntryHandler handler,
                            DuplicateResultVerifier duplicateVerifier) {
        this.state = state;
        this.handler = handler;
        this.duplicateVerifier = duplicateVerifier;
    }

    /**
     * 连续性规则：
     *
     * <pre>
     * N == last + 1  正常输出并推进
     * N <= last      重复事件，校验 templateId / requestSerialNum / payloadHash 一致后跳过
     * N > last + 1   发现缺口，停止扫描并告警
     * </pre>
     */
    public void onEntry(ResultLogEntry entry) {
        long last = state.getLastResultSerialNum();
        long serialNum = entry.resultSerialNum();

        if (serialNum == last + 1) {
            state.accept(entry);

            // 处理消息
            handler.onEntry(entry);
            return;
        }

        if (serialNum <= last) {
            if (duplicateVerifier.isSamePayload(entry, state)) {
                state.duplicate();
                return;
            }
            throw new ResultLogPayloadMismatchException(serialNum);
        }

        throw new ResultLogGapException(last + 1, serialNum);
    }

    public ResultLogScanState state() {
        return state;
    }
}
