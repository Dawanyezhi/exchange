package com.laser.exchange.resultpublisher.exception;

public class ResultLogGapException extends ResultLogReaderException {

    private final long expectedResultSerialNum;
    private final long actualResultSerialNum;

    public ResultLogGapException(long expectedResultSerialNum, long actualResultSerialNum) {
        super("resultSerialNum gap: expected=" + expectedResultSerialNum + ", actual=" + actualResultSerialNum);
        this.expectedResultSerialNum = expectedResultSerialNum;
        this.actualResultSerialNum = actualResultSerialNum;
    }

    public long getExpectedResultSerialNum() {
        return expectedResultSerialNum;
    }

    public long getActualResultSerialNum() {
        return actualResultSerialNum;
    }
}
