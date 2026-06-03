package com.laser.exchange.resultpublisher.archive;

public class ResultLogScanState {

    private long lastResultSerialNum;
    private ResultLogEntry lastAcceptedEntry;
    private long acceptedCount;
    private long duplicateCount;

    public ResultLogScanState() {
        this(0L);
    }

    public ResultLogScanState(long lastResultSerialNum) {
        this.lastResultSerialNum = lastResultSerialNum;
    }

    public long getLastResultSerialNum() {
        return lastResultSerialNum;
    }

    public long getAcceptedCount() {
        return acceptedCount;
    }

    public long getDuplicateCount() {
        return duplicateCount;
    }

    ResultLogEntry getLastAcceptedEntry() {
        return lastAcceptedEntry;
    }

    void accept(ResultLogEntry entry) {
        lastResultSerialNum = entry.resultSerialNum();
        lastAcceptedEntry = entry;
        acceptedCount++;
    }

    void duplicate() {
        duplicateCount++;
    }
}
