package com.laser.exchange.resultpublisher.archive;

@FunctionalInterface
public interface DuplicateResultVerifier {

    boolean isSamePayload(ResultLogEntry duplicate, ResultLogScanState state);

    static DuplicateResultVerifier lastAcceptedOnly() {
        return (duplicate, state) -> {
            ResultLogEntry lastAccepted = state.getLastAcceptedEntry();
            return lastAccepted != null
                    && duplicate.resultSerialNum() == lastAccepted.resultSerialNum()
                    && lastAccepted.samePayload(duplicate);
        };
    }
}
