package com.laser.exchange.resultpublisher.exception;

public class ResultLogUnknownTemplateException extends ResultLogReaderException {

    private final int templateId;

    public ResultLogUnknownTemplateException(int templateId) {
        super("unknown result templateId: " + templateId);
        this.templateId = templateId;
    }

    public int getTemplateId() {
        return templateId;
    }
}
