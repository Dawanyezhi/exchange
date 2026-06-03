package com.laser.exchange.resultpublisher.archive;

@FunctionalInterface
public interface ResultLogEntryHandler {

    void onEntry(ResultLogEntry entry);
}
