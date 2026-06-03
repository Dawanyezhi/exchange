package com.laser.exchange.resultpublisher.exception;

public class ResultLogPayloadMismatchException extends ResultLogReaderException {

    private final long resultSerialNum;

    public ResultLogPayloadMismatchException(long resultSerialNum) {
        super("duplicate resultSerialNum has different payload: resultSerialNum=" + resultSerialNum);
        this.resultSerialNum = resultSerialNum;
    }

    public long getResultSerialNum() {
        return resultSerialNum;
    }
}
