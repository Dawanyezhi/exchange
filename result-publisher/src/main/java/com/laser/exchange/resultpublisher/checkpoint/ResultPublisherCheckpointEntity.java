package com.laser.exchange.resultpublisher.checkpoint;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.laser.exchange.resultpublisher.archive.ResultLogEntry;

import java.time.LocalDateTime;
import java.util.Objects;

@TableName("result_publisher_checkpoint")
public class ResultPublisherCheckpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String resultChannel;

    private Integer resultStreamId;

    private Long recordingId;

    private Long nextReplayPosition;

    private Long lastResultSerialNum;

    private Long lastStartPosition;

    private Long lastEndPosition;

    private Long lastRequestSerialNum;

    private Integer lastTemplateId;

    private Integer lastPayloadHash;

    private Long version;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;

    public static ResultPublisherCheckpointEntity fromEntry(String resultChannel,
                                                            int resultStreamId,
                                                            ResultLogEntry entry) {
        Objects.requireNonNull(entry, "entry");

        ResultPublisherCheckpointEntity entity = new ResultPublisherCheckpointEntity();
        entity.setResultChannel(Objects.requireNonNull(resultChannel, "resultChannel"));
        entity.setResultStreamId(resultStreamId);
        entity.setRecordingId(entry.recordingId());
        entity.setNextReplayPosition(entry.endPosition());
        entity.setLastResultSerialNum(entry.resultSerialNum());
        entity.setLastStartPosition(entry.startPosition());
        entity.setLastEndPosition(entry.endPosition());
        entity.setLastRequestSerialNum(entry.requestSerialNum());
        entity.setLastTemplateId(entry.templateId());
        entity.setLastPayloadHash(entry.payloadHash());
        entity.setVersion(0L);
        return entity;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getResultChannel() {
        return resultChannel;
    }

    public void setResultChannel(String resultChannel) {
        this.resultChannel = resultChannel;
    }

    public Integer getResultStreamId() {
        return resultStreamId;
    }

    public void setResultStreamId(Integer resultStreamId) {
        this.resultStreamId = resultStreamId;
    }

    public Long getRecordingId() {
        return recordingId;
    }

    public void setRecordingId(Long recordingId) {
        this.recordingId = recordingId;
    }

    public Long getNextReplayPosition() {
        return nextReplayPosition;
    }

    public void setNextReplayPosition(Long nextReplayPosition) {
        this.nextReplayPosition = nextReplayPosition;
    }

    public Long getLastResultSerialNum() {
        return lastResultSerialNum;
    }

    public void setLastResultSerialNum(Long lastResultSerialNum) {
        this.lastResultSerialNum = lastResultSerialNum;
    }

    public Long getLastStartPosition() {
        return lastStartPosition;
    }

    public void setLastStartPosition(Long lastStartPosition) {
        this.lastStartPosition = lastStartPosition;
    }

    public Long getLastEndPosition() {
        return lastEndPosition;
    }

    public void setLastEndPosition(Long lastEndPosition) {
        this.lastEndPosition = lastEndPosition;
    }

    public Long getLastRequestSerialNum() {
        return lastRequestSerialNum;
    }

    public void setLastRequestSerialNum(Long lastRequestSerialNum) {
        this.lastRequestSerialNum = lastRequestSerialNum;
    }

    public Integer getLastTemplateId() {
        return lastTemplateId;
    }

    public void setLastTemplateId(Integer lastTemplateId) {
        this.lastTemplateId = lastTemplateId;
    }

    public Integer getLastPayloadHash() {
        return lastPayloadHash;
    }

    public void setLastPayloadHash(Integer lastPayloadHash) {
        this.lastPayloadHash = lastPayloadHash;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
