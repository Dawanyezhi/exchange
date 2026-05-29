package com.laser.exchange.matching.resultRepoModule;

import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.ResultBizTypeEnum;
import com.laser.exchange.common.enums.SystemErrorCodeEnum;
import com.laser.exchange.common.enums.SystemTypeEnum;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.common.result.PlaceOrderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryResultRepositoryTest {

    private InMemoryResultRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryResultRepository();
    }

    private PlaceOrderResult result(long resultSerialNum, long requestSerialNum) {
        return PlaceOrderResult.builder()
                .systemType(SystemTypeEnum.NORMAL)
                .systemErrorCode(SystemErrorCodeEnum.NONE)
                .resultBizType(ResultBizTypeEnum.PLACE_ORDER)
                .resultSerialNum(resultSerialNum)
                .requestSerialNum(requestSerialNum)
                .createTime(1000L + resultSerialNum)
                .orderId(resultSerialNum)
                .symbolCode(1)
                .symbolId("BTC_USDT")
                .orderStatus(OrderStatusEnum.NEW)
                .delegatePrice(new BigDecimal("100"))
                .delegateCount(BigDecimal.ONE)
                .build();
    }

    @Test
    void persistEmptyOrNullIsNoOp() {
        repo.persist(Collections.emptyList());
        repo.persist(null);
        assertEquals(0, repo.snapshot().size());
        assertEquals(0L, repo.getMaxResultSerialNum());
    }

    @Test
    void persistTracksMaxSerialNum() {
        repo.persist(Arrays.asList(result(1, 1), result(2, 1), result(3, 2)));
        assertEquals(3L, repo.getMaxResultSerialNum());
        assertEquals(3, repo.snapshot().size());
    }

    @Test
    void persistOrderingPreserved() {
        repo.persist(Arrays.asList(result(1, 1), result(2, 1)));
        repo.persist(Arrays.asList(result(3, 2)));

        List<MatchResult> all = repo.snapshot();
        assertEquals(1L, all.get(0).getResultSerialNum());
        assertEquals(2L, all.get(1).getResultSerialNum());
        assertEquals(3L, all.get(2).getResultSerialNum());
    }

    @Test
    void persistOutOfOrderRejected() {
        repo.persist(Arrays.asList(result(1, 1), result(2, 1)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> repo.persist(Arrays.asList(result(2, 2))));
        assertTrue(ex.getMessage().contains("out of order"));
    }

    @Test
    void persistDuplicateSerialNumRejected() {
        repo.persist(Arrays.asList(result(1, 1)));

        assertThrows(IllegalStateException.class,
                () -> repo.persist(Arrays.asList(result(1, 1))));
    }

    @Test
    void persistedSnapshotIsImmutable() {
        repo.persist(Arrays.asList(result(1, 1)));

        List<MatchResult> snap = repo.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.add(result(99, 99)));
    }
}
