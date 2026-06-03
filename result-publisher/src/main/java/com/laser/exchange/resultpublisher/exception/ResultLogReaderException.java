package com.laser.exchange.resultpublisher.exception;

public class ResultLogReaderException extends RuntimeException {

    public ResultLogReaderException(String message) {
        super(message);
    }

    public ResultLogReaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
