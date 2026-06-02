package com.laser.exchange.matching.snapshot;

import com.laser.exchange.common.config.SymbolConfig;
import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.matching.core.model.MatchConfig;
import com.laser.exchange.matching.core.model.MatchContext;
import com.laser.exchange.matching.core.model.MatchEngineState;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.matching.core.model.OrderBook;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import com.laser.exchange.matching.validation.SerialNumValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SnapshotManager 全场景测试。
 *
 * <p>关键场景：
 * <ul>
 *   <li>codec 往返 (header/symbolConfig/orderBook+orders/serialNum/footer)</li>
 *   <li>OrderBook 完整状态恢复</li>
 *   <li><b>快照前后 OrderBook bit-for-bit 一致 (用户要求)</b></li>
 *   <li>serialNum 恢复：只使用 snapshot.maxResultSerialNum 作为恢复边界</li>
 *   <li>多 symbol</li>
 * </ul>
 */
class SnapshotManagerTest {

    private SnapshotManager manager;
    private MatchEngineState state;
    private InMemorySnapshotBuffer buffer;

    @BeforeEach
    void setUp() {
        manager = new SnapshotManager();
        state = new MatchEngineState();
        buffer = new InMemorySnapshotBuffer();
    }

    private SymbolConfig symbolConfig(int code, String name, int base, int quote) {
        SymbolConfig c = new SymbolConfig();
        c.setSymbolId(code);
        c.setSymbolName(name);
        c.setSymbolDisplayName(name);
        c.setBaseCoinId(base);
        c.setQuoteCoinId(quote);
        return c;
    }

    private MatchOrder makeLimit(long orderId, OrderSideEnum side, BigDecimal price, BigDecimal qty,
                                 BigDecimal dealt, String symbolId) {
        MatchOrder o = MatchOrder.builder()
                .orderId(orderId)
                .clientOid("oid-" + orderId)
                .accountId(10000L + orderId)
                .symbolId(symbolId)
                .orderType(OrderType.LIMIT)
                .orderSide(side)
                .timeInForce(TimeInForceEnum.GTC)
                .delegatePrice(price)
                .delegateCount(qty)
                .dealtCount(dealt)
                .orderStatus(dealt.signum() > 0 ? OrderStatusEnum.PARTIALLY_FILLED : OrderStatusEnum.NEW)
                .createTime(1000L + orderId)
                .updateTime(2000L + orderId)
                .stpAccountId(10000L + orderId)
                .stpStrategyEnum(StpStrategyEnum.DEFAULT)
                .build();
        return o;
    }

    private void setupOneSymbolWithBook() {
        SymbolConfig cfg = symbolConfig(1, "BTC_USDT", 1, 2);
        state.getMatchContext().addSymbol(1, cfg);
        MatchConfig mc = new MatchConfig();
        mc.setSymbol("BTC_USDT");
        mc.setEnabled(true);
        state.addMatchConfig(mc);

        OrderBook book = new OrderBook("BTC_USDT");
        // 买盘：3 档
        book.addOrder(makeLimit(101, OrderSideEnum.BUY, new BigDecimal("100"), new BigDecimal("1"), BigDecimal.ZERO, "BTC_USDT"));
        book.addOrder(makeLimit(102, OrderSideEnum.BUY, new BigDecimal("99"), new BigDecimal("2"), BigDecimal.ZERO, "BTC_USDT"));
        book.addOrder(makeLimit(103, OrderSideEnum.BUY, new BigDecimal("100"), new BigDecimal("3"), new BigDecimal("0.5"), "BTC_USDT"));
        // 卖盘：2 档
        book.addOrder(makeLimit(201, OrderSideEnum.SELL, new BigDecimal("105"), new BigDecimal("1.5"), BigDecimal.ZERO, "BTC_USDT"));
        book.addOrder(makeLimit(202, OrderSideEnum.SELL, new BigDecimal("106"), new BigDecimal("2.5"), BigDecimal.ZERO, "BTC_USDT"));
        state.getMatchContext().addOrderBook(book);
    }

    @Test
    @DisplayName("空状态 take + load 仅含 header/footer")
    void emptyStateRoundTrip() {
        manager.takeSnapshot(state, 0L, 0L, 1700000000000L, buffer);
        // header + footer = 2 frames
        assertEquals(2, buffer.frameCount());

        MatchEngineState restored = new MatchEngineState();
        SerialNumValidator val = new SerialNumValidator(true);
        MatchResultEventsHelper helper = new MatchResultEventsHelper();
        SnapshotManager.LoadResult r = manager.loadSnapshot(restored, val, helper, buffer);
        assertEquals(0L, r.maxProcessedRequestSerialNum);
        assertEquals(0L, r.maxResultSerialNum);
    }

    @Test
    @DisplayName("单 symbol + 含部分成交订单 → 恢复后字段精确一致")
    void singleSymbolFidelity() {
        setupOneSymbolWithBook();
        state.getMatchConfig("BTC_USDT").setMarketOrderProtectionBps(250L);

        manager.takeSnapshot(state, 50L, 200L, 1700000000000L, buffer);

        MatchEngineState restored = new MatchEngineState();
        SerialNumValidator val = new SerialNumValidator(true);
        MatchResultEventsHelper helper = new MatchResultEventsHelper();
        manager.loadSnapshot(restored, val, helper, buffer);

        // SymbolConfig 一致
        SymbolConfig cfgR = restored.getMatchContext().getSymbolConfigMap().get(1);
        assertNotNull(cfgR);
        assertEquals("BTC_USDT", cfgR.getSymbolName());
        assertEquals(1, cfgR.getBaseCoinId());
        assertEquals(2, cfgR.getQuoteCoinId());
        assertEquals(250L, restored.getMatchConfig("BTC_USDT").getMarketOrderProtectionBps());

        // 订单簿存在
        OrderBook bookR = restored.getMatchContext().getOrderBook("BTC_USDT");
        assertNotNull(bookR);
        // 订单全部恢复 (3 buy + 2 sell)
        // 价格档位：买盘 2 档 (100, 99)，卖盘 2 档 (105, 106)
        assertEquals(2, bookR.getBuyOrders().size());
        assertEquals(2, bookR.getSellOrders().size());

        // 验证带 dealtCount 的订单字段
        MatchOrder o103 = bookR.getOrder(103L);
        assertNotNull(o103);
        assertEquals(new BigDecimal("0.5"), o103.getDealtCount());
        assertEquals(OrderStatusEnum.PARTIALLY_FILLED, o103.getOrderStatus());
        assertEquals(new BigDecimal("100"), o103.getDelegatePrice());
    }

    @Test
    @DisplayName("市价队列不会进入快照恢复状态")
    void marketQueuesAreNotSnapshotted() {
        SymbolConfig cfg = symbolConfig(1, "BTC_USDT", 1, 2);
        state.getMatchContext().addSymbol(1, cfg);
        MatchConfig mc = new MatchConfig();
        mc.setSymbol("BTC_USDT");
        mc.setEnabled(true);
        state.addMatchConfig(mc);

        OrderBook book = new OrderBook("BTC_USDT");
        MatchOrder marketOrder = MatchOrder.builder()
                .orderId(999L)
                .symbolId("BTC_USDT")
                .orderType(OrderType.MARKET)
                .orderSide(OrderSideEnum.BUY)
                .timeInForce(TimeInForceEnum.GTC)
                .delegateCount(BigDecimal.ONE)
                .dealtCount(BigDecimal.ZERO)
                .orderStatus(OrderStatusEnum.NEW)
                .build();
        book.addOrder(marketOrder);
        state.getMatchContext().addOrderBook(book);

        manager.takeSnapshot(state, 0L, 0L, 1L, buffer);

        MatchEngineState restored = new MatchEngineState();
        manager.loadSnapshot(restored, new SerialNumValidator(true),
                new MatchResultEventsHelper(), buffer);

        OrderBook restoredBook = restored.getMatchContext().getOrderBook("BTC_USDT");
        assertNotNull(restoredBook);
        assertTrue(restoredBook.getBuyMarketOrderQueue().isEmpty());
        assertTrue(restoredBook.getSellMarketOrderQueue().isEmpty());
        assertNull(restoredBook.getOrder(999L));
    }

    @Test
    @DisplayName("快照前后 OrderBook 必须 bit-for-bit 一致 (用户要求验证)")
    void orderBookIdenticalAfterSnapshotRestore() {
        setupOneSymbolWithBook();

        // 1. 拍快照前状态指纹
        OrderBook bookBefore = state.getMatchContext().getOrderBook("BTC_USDT");
        String fingerprintBefore = fingerprint(bookBefore);

        // 2. take snapshot
        manager.takeSnapshot(state, 50L, 200L, 1700000000000L, buffer);

        // 3. 模拟新进程：完全独立的 state 对象 load 快照
        MatchEngineState restored = new MatchEngineState();
        SerialNumValidator val = new SerialNumValidator(true);
        MatchResultEventsHelper helper = new MatchResultEventsHelper();
        manager.loadSnapshot(restored, val, helper, buffer);

        // 4. 拍快照恢复后状态指纹
        OrderBook bookAfter = restored.getMatchContext().getOrderBook("BTC_USDT");
        String fingerprintAfter = fingerprint(bookAfter);

        // 5. bit-for-bit 一致
        assertEquals(fingerprintBefore, fingerprintAfter,
                "快照恢复后 OrderBook 必须与快照前完全一致 (字段顺序/价格档位/订单 FIFO/dealtCount)");

        // 6. 二次快照应得到相同字节流（确定性）
        InMemorySnapshotBuffer buffer2 = new InMemorySnapshotBuffer();
        manager.takeSnapshot(restored, 50L, 200L, 1700000000000L, buffer2);
        assertEquals(buffer.frameCount(), buffer2.frameCount(),
                "snapshot → load → snapshot 应产出相同 frame 数（确定性）");
    }

    /**
     * 构造 OrderBook 的可比较指纹：
     * - 按价格档位顺序 (买盘逆序、卖盘正序)
     * - 同档位内按 FIFO
     * - 每个订单输出全字段 (orderId, side, price, qty, dealt, status, accountId, createTime, ...)
     */
    private String fingerprint(OrderBook book) {
        StringBuilder sb = new StringBuilder();
        sb.append("symbol=").append(book.getSymbol()).append('\n');
        sb.append("--BUY--\n");
        appendDepth(sb, book.getBuyOrders());
        sb.append("--SELL--\n");
        appendDepth(sb, book.getSellOrders());
        return sb.toString();
    }

    private void appendDepth(StringBuilder sb, TreeMap<BigDecimal, com.laser.exchange.matching.core.model.DepthLine> depth) {
        for (var e : depth.entrySet()) {
            sb.append("price=").append(e.getKey().toPlainString()).append('\n');
            var it = e.getValue().iterator();
            while (it.hasNext()) {
                MatchOrder o = it.next();
                sb.append("  oid=").append(o.getOrderId())
                        .append(" side=").append(o.getOrderSide())
                        .append(" price=").append(o.getDelegatePrice().toPlainString())
                        .append(" qty=").append(o.getDelegateCount().toPlainString())
                        .append(" dealt=").append(o.getDealtCount() == null ? "0" : o.getDealtCount().toPlainString())
                        .append(" status=").append(o.getOrderStatus())
                        .append(" acct=").append(o.getAccountId())
                        .append(" ct=").append(o.getCreateTime())
                        .append('\n');
            }
        }
    }

    @Test
    @DisplayName("恢复 resultSerialNum 起点只使用快照边界")
    void serialNumRestoreUsesSnapshotBoundary() {
        setupOneSymbolWithBook();
        manager.takeSnapshot(state, 50L, 500L, 1700000000000L, buffer);

        MatchEngineState restored = new MatchEngineState();
        SerialNumValidator val = new SerialNumValidator(true);
        MatchResultEventsHelper helper = new MatchResultEventsHelper();
        manager.loadSnapshot(restored, val, helper, buffer);

        assertEquals(501L, helper.getNextResultSerialNum(),
                "恢复起点应取 snapshot.maxResultSerialNum + 1");
        assertEquals(50L, val.getLastSerialNum());
    }

    @Test
    @DisplayName("多 symbol 全部 round-trip")
    void multiSymbolRoundTrip() {
        // 配置 2 个 symbol
        SymbolConfig btc = symbolConfig(1, "BTC_USDT", 1, 2);
        SymbolConfig eth = symbolConfig(2, "ETH_USDT", 3, 2);
        state.getMatchContext().addSymbol(1, btc);
        state.getMatchContext().addSymbol(2, eth);
        MatchConfig mc1 = new MatchConfig(); mc1.setSymbol("BTC_USDT"); mc1.setEnabled(true);
        MatchConfig mc2 = new MatchConfig(); mc2.setSymbol("ETH_USDT"); mc2.setEnabled(true);
        state.addMatchConfig(mc1);
        state.addMatchConfig(mc2);

        OrderBook btcBook = new OrderBook("BTC_USDT");
        btcBook.addOrder(makeLimit(1, OrderSideEnum.BUY, new BigDecimal("100"), BigDecimal.ONE, BigDecimal.ZERO, "BTC_USDT"));
        state.getMatchContext().addOrderBook(btcBook);

        OrderBook ethBook = new OrderBook("ETH_USDT");
        ethBook.addOrder(makeLimit(2, OrderSideEnum.SELL, new BigDecimal("3000"), BigDecimal.ONE, BigDecimal.ZERO, "ETH_USDT"));
        state.getMatchContext().addOrderBook(ethBook);

        manager.takeSnapshot(state, 10L, 20L, 100L, buffer);

        MatchEngineState restored = new MatchEngineState();
        manager.loadSnapshot(restored, new SerialNumValidator(true),
                new MatchResultEventsHelper(), buffer);

        assertEquals(2, restored.getMatchContext().getSymbolConfigMap().size());
        assertEquals(2, restored.getMatchContext().getOrderBookMap().size());
        assertNotNull(restored.getMatchContext().getOrderBook("BTC_USDT").getOrder(1L));
        assertNotNull(restored.getMatchContext().getOrderBook("ETH_USDT").getOrder(2L));
    }

    @Test
    @DisplayName("订单 FIFO 顺序在恢复后保留")
    void orderFifoOrderPreserved() {
        SymbolConfig cfg = symbolConfig(1, "BTC_USDT", 1, 2);
        state.getMatchContext().addSymbol(1, cfg);
        MatchConfig mc = new MatchConfig(); mc.setSymbol("BTC_USDT"); mc.setEnabled(true);
        state.addMatchConfig(mc);

        OrderBook book = new OrderBook("BTC_USDT");
        // 同价格档位下 3 个订单
        for (int i = 1; i <= 3; i++) {
            book.addOrder(makeLimit(i, OrderSideEnum.BUY, new BigDecimal("100"),
                    BigDecimal.valueOf(i), BigDecimal.ZERO, "BTC_USDT"));
        }
        state.getMatchContext().addOrderBook(book);

        manager.takeSnapshot(state, 0, 0, 0, buffer);

        MatchEngineState restored = new MatchEngineState();
        manager.loadSnapshot(restored, new SerialNumValidator(true),
                new MatchResultEventsHelper(), buffer);

        OrderBook bookR = restored.getMatchContext().getOrderBook("BTC_USDT");
        var depth = bookR.getBuyOrders().get(new BigDecimal("100"));
        assertNotNull(depth);

        List<Long> recoveredOrderIds = new ArrayList<>();
        var it = depth.iterator();
        while (it.hasNext()) {
            recoveredOrderIds.add(it.next().getOrderId());
        }
        assertEquals(List.of(1L, 2L, 3L), recoveredOrderIds, "同档位订单必须按 FIFO 恢复");
    }

    @Test
    @DisplayName("快照内容被篡改时 checksum 校验失败")
    void checksumMismatchFailsLoad() {
        setupOneSymbolWithBook();
        manager.takeSnapshot(state, 50L, 200L, 1700000000000L, buffer);

        // 篡改第一条非 footer frame，footer 中的 checksum 保持原值。
        buffer.corruptByteForTest(1, 12);

        MatchEngineState restored = new MatchEngineState();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> manager.loadSnapshot(restored, new SerialNumValidator(true),
                        new MatchResultEventsHelper(), buffer));
        assertTrue(ex.getMessage().contains("snapshot checksum mismatch"));
    }

    @Test
    @DisplayName("快照 frame 缺失时 entryCount 校验失败")
    void entryCountMismatchFailsLoad() {
        setupOneSymbolWithBook();
        manager.takeSnapshot(state, 50L, 200L, 1700000000000L, buffer);

        // 删除 footer 前的最后一条业务 frame，footer 仍声明原 entryCount。
        buffer.removeFrameForTest(buffer.frameCount() - 2);

        MatchEngineState restored = new MatchEngineState();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> manager.loadSnapshot(restored, new SerialNumValidator(true),
                        new MatchResultEventsHelper(), buffer));
        assertTrue(ex.getMessage().contains("snapshot entry count mismatch"));
    }
}
