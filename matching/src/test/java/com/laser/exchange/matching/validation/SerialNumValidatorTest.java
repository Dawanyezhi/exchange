package com.laser.exchange.matching.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SerialNumValidatorTest {

    @Test
    void validatesContinuousSequenceFromOne() {
        SerialNumValidator v = new SerialNumValidator(true);

        assertTrue(v.validateAndAdvance(1));
        assertTrue(v.validateAndAdvance(2));
        assertTrue(v.validateAndAdvance(3));
        assertEquals(3, v.getLastSerialNum());
    }

    @Test
    void rejectsGapInSequence() {
        SerialNumValidator v = new SerialNumValidator(true);

        assertTrue(v.validateAndAdvance(1));
        assertTrue(v.validateAndAdvance(2));
        // Gap: jumped 3, sent 4
        assertFalse(v.validateAndAdvance(4));
        // lastSerialNum stays at 2 (not advanced on failure)
        assertEquals(2, v.getLastSerialNum());
        // Recovery: sending 3 succeeds
        assertTrue(v.validateAndAdvance(3));
    }

    @Test
    void rejectsDuplicateSerialNum() {
        SerialNumValidator v = new SerialNumValidator(true);

        assertTrue(v.validateAndAdvance(1));
        assertFalse(v.validateAndAdvance(1));  // 重复
    }

    @Test
    void rejectsOutOfOrderSerialNum() {
        SerialNumValidator v = new SerialNumValidator(true);

        assertTrue(v.validateAndAdvance(1));
        assertTrue(v.validateAndAdvance(2));
        assertFalse(v.validateAndAdvance(1));  // 倒退
    }

    @Test
    void switchOffSkipsValidationButStillTracks() {
        SerialNumValidator v = new SerialNumValidator(false);

        // 跳号也会通过
        assertTrue(v.validateAndAdvance(1));
        assertTrue(v.validateAndAdvance(100));
        assertTrue(v.validateAndAdvance(50));   // 即使倒退
        // 但 lastSerialNum 仍跟踪到最近一次
        assertEquals(50, v.getLastSerialNum());
    }

    @Test
    void restoreLastSerialNumOverwrites() {
        SerialNumValidator v = new SerialNumValidator(true);
        v.validateAndAdvance(1);
        v.validateAndAdvance(2);

        v.restoreLastSerialNum(100);

        assertEquals(100, v.getLastSerialNum());
        assertTrue(v.validateAndAdvance(101));   // 继续从 101 开始
        assertFalse(v.validateAndAdvance(105));  // 又要严格 +1
    }

    @Test
    void firstSerialNumMustBeOne() {
        SerialNumValidator v = new SerialNumValidator(true);

        // lastSerialNum 起始为 0，所以第一个合法 serialNum 是 1
        assertFalse(v.validateAndAdvance(2));   // 跳过 1
        assertTrue(v.validateAndAdvance(1));
    }
}
