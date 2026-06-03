package com.laser.exchange.resultpublisher.checkpoint;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ResultPublisherCheckpointMapper extends BaseMapper<ResultPublisherCheckpointEntity> {

    @Select("""
            SELECT
                id,
                result_channel AS resultChannel,
                result_stream_id AS resultStreamId,
                recording_id AS recordingId,
                next_replay_position AS nextReplayPosition,
                last_result_serial_num AS lastResultSerialNum,
                last_start_position AS lastStartPosition,
                last_end_position AS lastEndPosition,
                last_request_serial_num AS lastRequestSerialNum,
                last_template_id AS lastTemplateId,
                last_payload_hash AS lastPayloadHash,
                version,
                created_at AS createdAt,
                updated_at AS updatedAt
            FROM result_publisher_checkpoint
            WHERE result_channel = #{resultChannel}
              AND result_stream_id = #{resultStreamId}
            """)
    ResultPublisherCheckpointEntity selectByResultStream(@Param("resultChannel") String resultChannel,
                                                         @Param("resultStreamId") int resultStreamId);

    @Insert("""
            INSERT INTO result_publisher_checkpoint (
                result_channel,
                result_stream_id,
                recording_id,
                next_replay_position,
                last_result_serial_num,
                last_start_position,
                last_end_position,
                last_request_serial_num,
                last_template_id,
                last_payload_hash,
                version,
                created_at,
                updated_at
            ) VALUES (
                #{checkpoint.resultChannel},
                #{checkpoint.resultStreamId},
                #{checkpoint.recordingId},
                #{checkpoint.nextReplayPosition},
                #{checkpoint.lastResultSerialNum},
                #{checkpoint.lastStartPosition},
                #{checkpoint.lastEndPosition},
                #{checkpoint.lastRequestSerialNum},
                #{checkpoint.lastTemplateId},
                #{checkpoint.lastPayloadHash},
                #{checkpoint.version},
                UTC_TIMESTAMP(6),
                UTC_TIMESTAMP(6)
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "checkpoint.id")
    int insertCheckpoint(@Param("checkpoint") ResultPublisherCheckpointEntity checkpoint);

    @Update("""
            UPDATE result_publisher_checkpoint
            SET
                recording_id = #{checkpoint.recordingId},
                next_replay_position = #{checkpoint.nextReplayPosition},
                last_result_serial_num = #{checkpoint.lastResultSerialNum},
                last_start_position = #{checkpoint.lastStartPosition},
                last_end_position = #{checkpoint.lastEndPosition},
                last_request_serial_num = #{checkpoint.lastRequestSerialNum},
                last_template_id = #{checkpoint.lastTemplateId},
                last_payload_hash = #{checkpoint.lastPayloadHash},
                updated_at = UTC_TIMESTAMP(6),
                version = version + 1
            WHERE result_channel = #{checkpoint.resultChannel}
              AND result_stream_id = #{checkpoint.resultStreamId}
              AND version = #{expectedVersion}
              AND (
                  #{checkpoint.lastResultSerialNum} > last_result_serial_num
                  OR (
                      #{checkpoint.lastResultSerialNum} = last_result_serial_num
                      AND #{checkpoint.nextReplayPosition} >= next_replay_position
                  )
              )
            """)
    int updateCheckpointIfVersionMatchesAndProgresses(
            @Param("checkpoint") ResultPublisherCheckpointEntity checkpoint,
            @Param("expectedVersion") long expectedVersion
    );
}
