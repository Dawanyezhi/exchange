package com.laser.exchange.matching.core.service;

import com.laser.exchange.common.enums.*;
import com.laser.exchange.matching.enums.*;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.matching.core.model.OrderBook;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StpStrategyService 单元测试 - 100%分支覆盖
 *
 * 分支覆盖分析:
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  processSTP 方法分支                                                   │
 * ├────────────────────────────────────────────────────────────────────────┤
 * │  1. stpAccountId < 0                      → OP_NORMAL                  │
 * │  2. stpStrategyEnum == null               → OP_NORMAL                  │
 * │  3. stpStrategyEnum == DEFAULT            → OP_NORMAL                  │
 * │  4. stpAccountId != maker.stpAccountId    → OP_NORMAL (不同账户)       │
 * │  5. CANCEL_TAKER + FOK                    → OP_BREAK                   │
 * │  6. CANCEL_TAKER + 非FOK                  → taker撤单, OP_BREAK        │
 * │  7. CANCEL_MAKER + FOK                    → OP_CONTINUE                │
 * │  8. CANCEL_MAKER + 非FOK                  → maker撤单, OP_CONTINUE     │
 * │  9. CANCEL_BOTH + FOK                     → OP_BREAK                   │
 * │ 10. CANCEL_BOTH + 非FOK                   → 双方撤单, OP_BREAK         │
 * │ 11. switch default                        → OP_NORMAL (不可达)         │
 * └────────────────────────────────────────────────────────────────────────┘
 */
class StpStrategyServiceTest {

    private StpStrategyService stpStrategyService;
    private OrderBook orderBook;
    private List<MatchOrder> pendingRemoves;

    private static final String SYMBOL = "SPOT_BTC_USDT";
    private static final long SAME_ACCOUNT_ID = 1001L;
    private static final long DIFFERENT_ACCOUNT_ID = 2002L;

    @BeforeEach
    void setUp() {
        stpStrategyService = new StpStrategyService();
        orderBook = new OrderBook(SYMBOL);
        pendingRemoves = new ArrayList<>();
    }

    // ==================== 辅助方法 ====================

    private MatchOrder buildOrder(long orderId, OrderSideEnum side, TimeInForceEnum tif,
                                  StpStrategyEnum stpStrategy, long stpAccountId) {
        return MatchOrder.builder()
                .orderId(orderId)
                .symbolId(SYMBOL)
                .orderType(OrderType.LIMIT)
                .orderSide(side)
                .timeInForce(tif)
                .stpStrategyEnum(stpStrategy)
                .stpAccountId(stpAccountId)
                .delegatePrice(new BigDecimal("50000"))
                .delegateCount(new BigDecimal("1"))
                .dealtCount(BigDecimal.ZERO)
                .orderStatus(OrderStatusEnum.NEW)
                .build();
    }

    private MatchOrder buildTaker(StpStrategyEnum stpStrategy, long stpAccountId, TimeInForceEnum tif) {
        return buildOrder(1L, OrderSideEnum.BUY, tif, stpStrategy, stpAccountId);
    }

    private MatchOrder buildMaker(long stpAccountId) {
        return buildOrder(2L, OrderSideEnum.SELL, TimeInForceEnum.GTC, StpStrategyEnum.DEFAULT, stpAccountId);
    }

    // ==================== 不触发自成交保护的场景 ====================

    @Nested
    @DisplayName("不触发自成交保护 - 返回 OP_NORMAL")
    class NoStpProtectionTest {

        @Test
        @DisplayName("stpAccountId < 0 时不触发自成交保护")
        void processSTP_negativeStpAccountId_shouldReturnNormal() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_TAKER, -1L, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_NORMAL, result);
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }

        @Test
        @DisplayName("stpStrategyEnum == null 时不触发自成交保护")
        void processSTP_nullStrategy_shouldReturnNormal() {
            MatchOrder taker = buildTaker(null, SAME_ACCOUNT_ID, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_NORMAL, result);
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }

        @Test
        @DisplayName("stpStrategyEnum == DEFAULT 时不触发自成交保护")
        void processSTP_defaultStrategy_shouldReturnNormal() {
            MatchOrder taker = buildTaker(StpStrategyEnum.DEFAULT, SAME_ACCOUNT_ID, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_NORMAL, result);
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }

        @Test
        @DisplayName("taker和maker账户不同时不触发自成交保护")
        void processSTP_differentAccounts_shouldReturnNormal() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_TAKER, SAME_ACCOUNT_ID, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(DIFFERENT_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_NORMAL, result);
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }
    }

    // ==================== CANCEL_TAKER 策略 ====================

    @Nested
    @DisplayName("CANCEL_TAKER 策略")
    class CancelTakerTest {

        @Test
        @DisplayName("CANCEL_TAKER + FOK订单 - 返回OP_BREAK但不撤单")
        void processSTP_cancelTaker_withFOK_shouldBreakWithoutCancel() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_TAKER, SAME_ACCOUNT_ID, TimeInForceEnum.FOK);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_BREAK, result);
            // FOK订单不立即撤单，由FOK逻辑统一处理
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }

        @Test
        @DisplayName("CANCEL_TAKER + 非FOK订单 - 撤销taker并返回OP_BREAK")
        void processSTP_cancelTaker_nonFOK_shouldCancelTakerAndBreak() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_TAKER, SAME_ACCOUNT_ID, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_BREAK, result);
            assertEquals(OrderStatusEnum.CANCELLED, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }

        @Test
        @DisplayName("CANCEL_TAKER + IOC订单 - 撤销taker并返回OP_BREAK")
        void processSTP_cancelTaker_IOC_shouldCancelTakerAndBreak() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_TAKER, SAME_ACCOUNT_ID, TimeInForceEnum.IOC);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_BREAK, result);
            assertEquals(OrderStatusEnum.CANCELLED, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }
    }

    // ==================== CANCEL_MAKER 策略 ====================

    @Nested
    @DisplayName("CANCEL_MAKER 策略")
    class CancelMakerTest {

        @Test
        @DisplayName("CANCEL_MAKER + FOK订单 - 返回OP_CONTINUE但不撤单")
        void processSTP_cancelMaker_withFOK_shouldContinueWithoutCancel() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_MAKER, SAME_ACCOUNT_ID, TimeInForceEnum.FOK);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_CONTINUE, result);
            // FOK订单不立即撤单
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }

        @Test
        @DisplayName("CANCEL_MAKER + 非FOK订单 - 撤销maker并加入待删除列表")
        void processSTP_cancelMaker_nonFOK_shouldCancelMakerAndContinue() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_MAKER, SAME_ACCOUNT_ID, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_CONTINUE, result);
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.CANCELLED, maker.getOrderStatus());
            assertEquals(1, pendingRemoves.size());
            assertSame(maker, pendingRemoves.get(0));
        }

        @Test
        @DisplayName("CANCEL_MAKER + POST_ONLY订单 - 撤销maker并加入待删除列表")
        void processSTP_cancelMaker_postOnly_shouldCancelMakerAndContinue() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_MAKER, SAME_ACCOUNT_ID, TimeInForceEnum.POST_ONLY);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_CONTINUE, result);
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.CANCELLED, maker.getOrderStatus());
            assertEquals(1, pendingRemoves.size());
            assertSame(maker, pendingRemoves.get(0));
        }
    }

    // ==================== CANCEL_BOTH 策略 ====================

    @Nested
    @DisplayName("CANCEL_BOTH 策略")
    class CancelBothTest {

        @Test
        @DisplayName("CANCEL_BOTH + FOK订单 - 返回OP_BREAK但不撤单")
        void processSTP_cancelBoth_withFOK_shouldBreakWithoutCancel() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_BOTH, SAME_ACCOUNT_ID, TimeInForceEnum.FOK);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_BREAK, result);
            // FOK订单不立即撤单
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }

        @Test
        @DisplayName("CANCEL_BOTH + 非FOK订单 - 撤销双方并加入待删除列表")
        void processSTP_cancelBoth_nonFOK_shouldCancelBothAndBreak() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_BOTH, SAME_ACCOUNT_ID, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_BREAK, result);
            assertEquals(OrderStatusEnum.CANCELLED, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.CANCELLED, maker.getOrderStatus());
            assertEquals(1, pendingRemoves.size());
            assertSame(maker, pendingRemoves.get(0));
        }

        @Test
        @DisplayName("CANCEL_BOTH + IOC订单 - 撤销双方并加入待删除列表")
        void processSTP_cancelBoth_IOC_shouldCancelBothAndBreak() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_BOTH, SAME_ACCOUNT_ID, TimeInForceEnum.IOC);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_BREAK, result);
            assertEquals(OrderStatusEnum.CANCELLED, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.CANCELLED, maker.getOrderStatus());
            assertEquals(1, pendingRemoves.size());
            assertSame(maker, pendingRemoves.get(0));
        }
    }

    // ==================== 边界场景 ====================

    @Nested
    @DisplayName("边界场景")
    class EdgeCaseTest {

        @Test
        @DisplayName("stpAccountId == 0 时应触发自成交保护")
        void processSTP_zeroStpAccountId_shouldTriggerProtection() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_TAKER, 0L, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(0L);

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            // stpAccountId == 0 >= 0，且账户匹配，应触发保护
            assertEquals(OpEnum.OP_BREAK, result);
            assertEquals(OrderStatusEnum.CANCELLED, taker.getOrderStatus());
        }

        @Test
        @DisplayName("多次调用processSTP应累积pendingRemoves")
        void processSTP_multipleCalls_shouldAccumulatePendingRemoves() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_MAKER, SAME_ACCOUNT_ID, TimeInForceEnum.GTC);
            MatchOrder maker1 = buildMaker(SAME_ACCOUNT_ID);
            maker1.setOrderId(101L);
            MatchOrder maker2 = buildMaker(SAME_ACCOUNT_ID);
            maker2.setOrderId(102L);

            stpStrategyService.processSTP(taker, maker1, pendingRemoves);

            // 重置taker状态用于下次调用
            taker.setOrderStatus(OrderStatusEnum.NEW);
            stpStrategyService.processSTP(taker, maker2, pendingRemoves);

            assertEquals(2, pendingRemoves.size());
            assertTrue(pendingRemoves.contains(maker1));
            assertTrue(pendingRemoves.contains(maker2));
        }

        @Test
        @DisplayName("maker的stpAccountId不同时不触发保护 - 边界值测试")
        void processSTP_makerDifferentAccount_boundary() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_BOTH, 100L, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(101L); // 仅差1

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_NORMAL, result);
            assertEquals(OrderStatusEnum.NEW, taker.getOrderStatus());
            assertEquals(OrderStatusEnum.NEW, maker.getOrderStatus());
            assertTrue(pendingRemoves.isEmpty());
        }
    }

    // ==================== 综合场景 ====================

    @Nested
    @DisplayName("综合场景")
    class IntegrationTest {

        @Test
        @DisplayName("完整自成交保护流程 - CANCEL_MAKER策略验证pendingRemoves正确性")
        void processSTP_cancelMaker_verifyPendingRemovesIntegrity() {
            MatchOrder taker = buildTaker(StpStrategyEnum.CANCEL_MAKER, SAME_ACCOUNT_ID, TimeInForceEnum.GTC);
            MatchOrder maker = buildMaker(SAME_ACCOUNT_ID);

            // 模拟maker已在订单簿中
            orderBook.addOrder(maker);
            assertTrue(orderBook.isOrderExists(maker.getOrderId()));

            OpEnum result = stpStrategyService.processSTP(taker, maker, pendingRemoves);

            assertEquals(OpEnum.OP_CONTINUE, result);
            assertEquals(OrderStatusEnum.CANCELLED, maker.getOrderStatus());
            assertEquals(1, pendingRemoves.size());

            // 模拟后续统一删除
            for (MatchOrder order : pendingRemoves) {
                orderBook.removeOrder(order);
            }
            assertFalse(orderBook.isOrderExists(maker.getOrderId()));
        }
    }
}
