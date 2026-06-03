package com.laser.exchange.resultpublisher.archive;

import com.laser.exchange.resultpublisher.exception.ResultLogReaderException;
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.BitUtil;
import org.agrona.DirectBuffer;

import java.util.Objects;

/**
 * 从 Aeron Archive result-data-stream 读取 MatchResult。
 *
 * <p>这个类只做读取和连续性校验，不持久化索引、不提供 replay API、不做实时推送。
 */
public class ArchiveResultLogReader {

    private final AeronArchive archive;
    private final ArchiveResultLogReaderConfig config;
    private final ResultFrameDecoder decoder;

    public ArchiveResultLogReader(AeronArchive archive, ArchiveResultLogReaderConfig config) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.config = Objects.requireNonNull(config, "config");
        this.decoder = new ResultFrameDecoder();
    }

    public long findLatestResultRecordingId() {
        long recordingId = archive.findLastMatchingRecording(
                0L,
                config.resultChannel(),
                config.resultStreamId(),
                0
        );
        if (recordingId == Aeron.NULL_VALUE) {
            throw new ResultLogReaderException("result recording not found, channel="
                    + config.resultChannel() + ", streamId=" + config.resultStreamId());
        }
        return recordingId;
    }

    public ResultLogScanState replayFrom(long position, ResultLogEntryHandler handler) {
        return replay(findLatestResultRecordingId(), position, AeronArchive.REPLAY_ALL_AND_STOP, handler);
    }

    public ResultLogScanState replay(long recordingId,
                                     long position,
                                     long length,
                                     ResultLogEntryHandler handler) {
        ResultLogScanState state = new ResultLogScanState();
        replay(recordingId, position, length, new ResultLogScanner(state, handler));
        return state;
    }

    public void replay(long recordingId,
                       long position,
                       long length,
                       ResultLogScanner scanner) {
        try (Subscription subscription = archive.replay(
                recordingId,
                position,
                length,
                config.replayChannel(),
                config.replayStreamId())) {
            FragmentHandler handler = new FragmentAssembler(new DecodingFragmentHandler(recordingId, scanner));
            pollUntilEndOfReplay(subscription, handler);
        }
    }

    private void pollUntilEndOfReplay(Subscription subscription, FragmentHandler handler) {
        int idleSpins = 0;
        while (true) {
            int fragments = subscription.poll(handler, config.fragmentLimit());
            if (fragments > 0) {
                idleSpins = 0;
                continue;
            }

            if (isReplayComplete(subscription)) {
                return;
            }

            if (++idleSpins > config.idleSpinLimit()) {
                throw new ResultLogReaderException("replay made no progress, channel="
                        + config.replayChannel() + ", streamId=" + config.replayStreamId());
            }
            Thread.onSpinWait();
        }
    }

    private boolean isReplayComplete(Subscription subscription) {
        for (int i = 0; i < subscription.imageCount(); i++) {
            Image image = subscription.imageAtIndex(i);
            if (image.isEndOfStream()) {
                return true;
            }
        }
        return false;
    }

    private final class DecodingFragmentHandler implements FragmentHandler {

        private final long recordingId;
        private final ResultLogScanner scanner;

        private DecodingFragmentHandler(long recordingId, ResultLogScanner scanner) {
            this.recordingId = recordingId;
            this.scanner = scanner;
        }

        @Override
        public void onFragment(DirectBuffer buffer, int offset, int length, Header header) {
            long endPosition = header.position();
            long startPosition = endPosition - alignFrameLength(length);
            ResultLogEntry entry = decoder.decode(buffer, offset, length, recordingId, startPosition, endPosition);
            scanner.onEntry(entry);
        }
    }

    private static long alignFrameLength(int payloadLength) {
        return BitUtil.align(payloadLength + DataHeaderFlyweight.HEADER_LENGTH, FrameDescriptor.FRAME_ALIGNMENT);
    }
}
