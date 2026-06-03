package com.laser.exchange.resultpublisher.archive;

import com.laser.exchange.resultpublisher.exception.ResultLogGapException;
import com.laser.exchange.resultpublisher.exception.ResultLogPayloadMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.laser.exchange.resultpublisher.archive.ResultFrameDecoderTest.encode;
import static com.laser.exchange.resultpublisher.archive.ResultFrameDecoderTest.placeResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResultLogScannerTest {

    private final ResultFrameDecoder decoder = new ResultFrameDecoder();

    @Test
    @DisplayName("连续 resultSerialNum 正常输出并推进")
    void acceptsStrictlyContinuousResults() {
        List<ResultLogEntry> accepted = new ArrayList<>();
        ResultLogScanner scanner = new ResultLogScanner(accepted::add);

        scanner.onEntry(entry(1L, "1.00"));
        scanner.onEntry(entry(2L, "2.00"));

        assertEquals(2, accepted.size());
        assertEquals(2L, scanner.state().getLastResultSerialNum());
        assertEquals(2L, scanner.state().getAcceptedCount());
        assertEquals(0L, scanner.state().getDuplicateCount());
    }

    @Test
    @DisplayName("重复 resultSerialNum payload 一致时跳过")
    void skipsDuplicateWhenPayloadIsSame() {
        List<ResultLogEntry> accepted = new ArrayList<>();
        ResultLogScanner scanner = new ResultLogScanner(accepted::add);
        ResultLogEntry first = entry(1L, "1.00");

        scanner.onEntry(first);
        scanner.onEntry(first);

        assertEquals(1, accepted.size());
        assertEquals(1L, scanner.state().getLastResultSerialNum());
        assertEquals(1L, scanner.state().getAcceptedCount());
        assertEquals(1L, scanner.state().getDuplicateCount());
    }

    @Test
    @DisplayName("同 resultSerialNum payload 不一致时停止")
    void failsWhenDuplicatePayloadDiffers() {
        ResultLogScanner scanner = new ResultLogScanner(entry -> {
        });

        scanner.onEntry(entry(1L, "1.00"));

        assertThrows(ResultLogPayloadMismatchException.class, () -> scanner.onEntry(entry(1L, "9.99")));
    }

    @Test
    @DisplayName("resultSerialNum 跳号时停止")
    void failsOnGap() {
        ResultLogScanner scanner = new ResultLogScanner(entry -> {
        });

        scanner.onEntry(entry(1L, "1.00"));

        ResultLogGapException ex = assertThrows(ResultLogGapException.class, () -> scanner.onEntry(entry(3L, "3.00")));
        assertEquals(2L, ex.getExpectedResultSerialNum());
        assertEquals(3L, ex.getActualResultSerialNum());
    }

    @Test
    @DisplayName("历史重复由外部 verifier 校验一致后跳过")
    void skipsOlderDuplicateWhenVerifierConfirmsSamePayload() {
        List<ResultLogEntry> accepted = new ArrayList<>();
        ResultLogScanState state = new ResultLogScanState();
        ResultLogScanner scanner = new ResultLogScanner(state, accepted::add, (duplicate, scanState) -> true);

        scanner.onEntry(entry(1L, "1.00"));
        scanner.onEntry(entry(2L, "2.00"));
        scanner.onEntry(entry(1L, "1.00"));

        assertEquals(2, accepted.size());
        assertEquals(2L, scanner.state().getLastResultSerialNum());
        assertEquals(1L, scanner.state().getDuplicateCount());
    }

    private ResultLogEntry entry(long resultSerialNum, String delegatePrice) {
        var frame = encode(placeResult(resultSerialNum, 1_000L + resultSerialNum, delegatePrice));
        return decoder.decode(frame.buffer(), 0, frame.length(), 7L, resultSerialNum * 128L, resultSerialNum * 128L + 128L);
    }
}
