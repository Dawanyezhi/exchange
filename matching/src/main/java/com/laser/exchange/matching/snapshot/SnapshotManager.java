package com.laser.exchange.matching.snapshot;

import com.laser.exchange.common.codec.snapshot.MatchOrderEntryDecoder;
import com.laser.exchange.common.codec.snapshot.MatchOrderEntryEncoder;
import com.laser.exchange.common.codec.snapshot.MessageHeaderDecoder;
import com.laser.exchange.common.codec.snapshot.MessageHeaderEncoder;
import com.laser.exchange.common.codec.snapshot.OrderBookStartDecoder;
import com.laser.exchange.common.codec.snapshot.OrderBookStartEncoder;
import com.laser.exchange.common.codec.snapshot.SnapOrderSide;
import com.laser.exchange.common.codec.snapshot.SnapOrderStatus;
import com.laser.exchange.common.codec.snapshot.SnapOrderType;
import com.laser.exchange.common.codec.snapshot.SnapStpStrategy;
import com.laser.exchange.common.codec.snapshot.SnapTimeInForce;
import com.laser.exchange.common.codec.snapshot.SnapshotFooterDecoder;
import com.laser.exchange.common.codec.snapshot.SnapshotFooterEncoder;
import com.laser.exchange.common.codec.snapshot.SnapshotHeaderDecoder;
import com.laser.exchange.common.codec.snapshot.SnapshotHeaderEncoder;
import com.laser.exchange.common.codec.snapshot.SymbolConfigEntryDecoder;
import com.laser.exchange.common.codec.snapshot.SymbolConfigEntryEncoder;
import com.laser.exchange.common.codec.snapshot.SymbolSerialNumEntryDecoder;
import com.laser.exchange.common.codec.snapshot.SymbolSerialNumEntryEncoder;
import com.laser.exchange.common.codec.snapshot.BooleanType;
import com.laser.exchange.common.config.SymbolConfig;
import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderStatusEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.matching.core.model.DepthLine;
import com.laser.exchange.matching.core.model.MatchConfig;
import com.laser.exchange.matching.core.model.MatchContext;
import com.laser.exchange.matching.core.model.MatchEngineState;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.matching.core.model.OrderBook;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import com.laser.exchange.common.utils.BigDecimalUtil;
import com.laser.exchange.matching.config.MarketOrderConfig;
import com.laser.exchange.matching.validation.SerialNumValidator;
import lombok.extern.slf4j.Slf4j;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32C;

/**
 * 快照管理器：take / load 撮合引擎全量状态。
 *
 * <p><b>顶层设计</b>：SnapshotManager 与底层 Aeron/文件解耦，通过 {@link SnapshotWriter} /
 * {@link SnapshotReader} 注入收发端点。测试直接用 in-memory 实现，生产用 Aeron 实现。
 *
 * <p><b>快照内容</b>：
 * <ol>
 *   <li>{@link SnapshotHeaderEncoder} — maxProcessedRequestSerialNum / maxResultSerialNum + 时间戳</li>
 *   <li>每个 symbol 的 {@link SymbolConfigEntryEncoder}</li>
 *   <li>每个 symbol 的 {@link OrderBookStartEncoder}，后跟 N 个 {@link MatchOrderEntryEncoder}</li>
 *   <li>每个 symbol 的 {@link SymbolSerialNumEntryEncoder}</li>
 *   <li>{@link SnapshotFooterEncoder} — entry 计数</li>
 * </ol>
 *
 * <p><b>确定性恢复顺序</b>：load 后会按顺序：
 * <ol>
 *   <li>SymbolConfig → 填入 MatchContext.symbolConfigMap + 对应 MatchConfig</li>
 *   <li>OrderBookStart + MatchOrderEntry 按原顺序 {@code orderBook.addOrder(...)}，保证深度按价格+时间还原</li>
 *   <li>SerialNumValidator.lastSerialNum = header.maxProcessedRequestSerialNum</li>
 *   <li>EventsHelper.nextResultSerialNum = header.maxResultSerialNum + 1</li>
 * </ol>
 */
@Slf4j
public class SnapshotManager {

    private static final int SCHEMA_ID = 3;
    private static final int SNAPSHOT_VERSION = 1;

    /** ExpandableArrayBuffer 可动态扩容，单笔 write 不会超 MTU 但仍保留安全余量 */
    private final MutableDirectBuffer encodeBuffer = new ExpandableArrayBuffer(8 * 1024);
    private final long defaultMarketOrderProtectionBps;

    public SnapshotManager() {
        this(MarketOrderConfig.DEFAULT_PROTECTION_BPS);
    }

    public SnapshotManager(long defaultMarketOrderProtectionBps) {
        this.defaultMarketOrderProtectionBps = MarketOrderConfig.normalizeProtectionBps(defaultMarketOrderProtectionBps);
    }

    // ============ TAKE ============

    /**
     * 将当前撮合引擎状态快照化。
     *
     * @param state 当前引擎状态
     * @param maxProcessedRequestSerialNum 最近处理的 request serialNum（来自 SerialNumValidator）
     * @param maxResultSerialNum 最近分配的 result serialNum（EventsHelper.getNextResultSerialNum - 1）
     * @param writer 写出端（Aeron/File/Memory）
     * @return 写入的 entry 总数（含 header/footer）
     */
    public int takeSnapshot(MatchEngineState state,
                            long maxProcessedRequestSerialNum,
                            long maxResultSerialNum,
                            long takenAtMillis,
                            SnapshotWriter writer) {

        int entryCount = 0;
        SnapshotChecksum checksum = new SnapshotChecksum();

        // 1. Header
        // 最大请求序列号、最大结果序列号、快照生成时间
        entryCount += writeHeader(writer, checksum, takenAtMillis, maxProcessedRequestSerialNum, maxResultSerialNum);

        MatchContext ctx = state.getMatchContext();

        // 2. SymbolConfig 币对配置
        for (Map.Entry<Integer, SymbolConfig> e : ctx.getSymbolConfigMap().entrySet()) {
            SymbolConfig cfg = e.getValue();
            MatchConfig matchCfg = state.getMatchConfig(cfg.getSymbolName());
            boolean enabled = matchCfg != null && matchCfg.isEnabled();
            long protectionBps = matchCfg != null
                    ? MarketOrderConfig.normalizeProtectionBps(matchCfg.getMarketOrderProtectionBps())
                    : defaultMarketOrderProtectionBps;
            entryCount += writeSymbolConfig(writer, checksum, e.getKey(), cfg, enabled, protectionBps);
        }

        // 3. OrderBook + MatchOrders 订单簿订单
        for (Map.Entry<String, OrderBook> e : ctx.getOrderBookMap().entrySet()) {
            OrderBook book = e.getValue();
            int symbolCode = findSymbolCode(ctx, e.getKey());
            List<MatchOrder> allOrders = collectOrdersInBookOrder(book);

            // 该交易对下有多少订单
            entryCount += writeOrderBookStart(writer, checksum, symbolCode, e.getKey(), allOrders.size());
            for (MatchOrder o : allOrders) {
                entryCount += writeMatchOrder(writer, checksum, o);
            }
            // 每个 symbol 的 serialNum（当前简化版 per-symbol 记录只存全局值；Sprint 2 后续可扩展）
            entryCount += writeSymbolSerialNum(writer, checksum, symbolCode, maxProcessedRequestSerialNum, maxResultSerialNum);
        }

        // 4. Footer
        entryCount += writeFooter(writer, entryCount, checksum.value());

        log.info("[SnapshotManager] takeSnapshot done, entries={}, maxReq={}, maxRes={}",
                entryCount, maxProcessedRequestSerialNum, maxResultSerialNum);
        return entryCount;
    }

    private int findSymbolCode(MatchContext ctx, String symbolId) {
        for (Map.Entry<Integer, SymbolConfig> e : ctx.getSymbolConfigMap().entrySet()) {
            if (symbolId.equals(e.getValue().getSymbolName())) {
                    return e.getKey();
            }
        }
        return 0;
    }

    /**
     * 按 OrderBook 内部顺序导出订单：先按价格档位 (buy 逆序/sell 正序)，同档位内 FIFO。
     * 这样 load 时直接顺序 addOrder 就能重建等价结构。
     */
    private List<MatchOrder> collectOrdersInBookOrder(OrderBook book) {
        List<MatchOrder> out = new ArrayList<>();

        // 价格档位已使用红黑树实现排序
        for (DepthLine line : book.getBuyOrders().values()) {
            out.addAll(collectDepthLine(line));
        }
        for (DepthLine line : book.getSellOrders().values()) {
            out.addAll(collectDepthLine(line));
        }

        return out;
    }

    private Collection<MatchOrder> collectDepthLine(DepthLine line) {
        // DepthLine 暴露 iterator() 走 FIFO 顺序
        List<MatchOrder> copy = new ArrayList<>();
        var it = line.iterator();
        while (it.hasNext()) {
            copy.add(it.next());
        }
        return copy;
    }

    private int writeHeader(SnapshotWriter writer, SnapshotChecksum checksum, long takenAt, long maxReq, long maxRes) {
        SnapshotHeaderEncoder enc = new SnapshotHeaderEncoder();
        MessageHeaderEncoder h = new MessageHeaderEncoder();
        enc.wrapAndApplyHeader(encodeBuffer, 0, h);
        enc.snapshotVersion(SNAPSHOT_VERSION);
        enc.takenAtMillis(takenAt);
        enc.maxProcessedRequestSerialNum(maxReq);
        enc.maxResultSerialNum(maxRes);
        writeChecksummed(writer, checksum, MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength());
        return 1;
    }

    private int writeSymbolConfig(SnapshotWriter writer, SnapshotChecksum checksum, int symbolCode,
                                  SymbolConfig cfg, boolean enabled, long protectionBps) {
        SymbolConfigEntryEncoder enc = new SymbolConfigEntryEncoder();
        MessageHeaderEncoder h = new MessageHeaderEncoder();
        enc.wrapAndApplyHeader(encodeBuffer, 0, h);
        enc.symbolCode(symbolCode);
            enc.baseCoinId(cfg.getBaseCoinId());
            enc.quoteCoinId(cfg.getQuoteCoinId());
        enc.enabled(enabled ? BooleanType.TRUE : BooleanType.FALSE);
        enc.marketOrderProtectionBps(protectionBps);
        enc.symbolName(cfg.getSymbolName() != null ? cfg.getSymbolName() : "");
        writeChecksummed(writer, checksum, MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength());
        return 1;
    }

    private int writeOrderBookStart(SnapshotWriter writer, SnapshotChecksum checksum, int symbolCode, String symbolId, int orderCount) {
        OrderBookStartEncoder enc = new OrderBookStartEncoder();
        MessageHeaderEncoder h = new MessageHeaderEncoder();
        enc.wrapAndApplyHeader(encodeBuffer, 0, h);
        enc.symbolCode(symbolCode);
        enc.orderCount(orderCount);
        enc.symbolId(symbolId);
        writeChecksummed(writer, checksum, MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength());
        return 1;
    }

    private int writeMatchOrder(SnapshotWriter writer, SnapshotChecksum checksum, MatchOrder o) {
        MatchOrderEntryEncoder enc = new MatchOrderEntryEncoder();
        MessageHeaderEncoder h = new MessageHeaderEncoder();
        enc.wrapAndApplyHeader(encodeBuffer, 0, h);
        enc.orderId(o.getOrderId());
        enc.accountId(o.getAccountId());
        enc.orderType(mapOrderType(o.getOrderType()));
        enc.orderSide(mapOrderSide(o.getOrderSide()));
        enc.timeInForce(mapTif(o.getTimeInForce()));
        enc.stpStrategy(mapStp(o.getStpStrategyEnum()));
        enc.stpAccountId(o.getStpAccountId());
        enc.orderStatus(mapOrderStatus(o.getOrderStatus()));
        enc.createTime(o.getCreateTime());
        enc.updateTime(o.getUpdateTime());
        enc.delegatePrice(BigDecimalUtil.defaultToString(o.getDelegatePrice()));
        enc.delegateCount(BigDecimalUtil.defaultToString(o.getDelegateCount()));
        enc.dealtCount(BigDecimalUtil.defaultToString(o.getDealtCount()));
        enc.clientOid(o.getClientOid() != null ? o.getClientOid() : "");
        writeChecksummed(writer, checksum, MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength());
        return 1;
    }

    private int writeSymbolSerialNum(SnapshotWriter writer, SnapshotChecksum checksum, int symbolCode, long lastReq, long lastRes) {
        SymbolSerialNumEntryEncoder enc = new SymbolSerialNumEntryEncoder();
        MessageHeaderEncoder h = new MessageHeaderEncoder();
        enc.wrapAndApplyHeader(encodeBuffer, 0, h);
        enc.symbolCode(symbolCode);
        enc.lastRequestSerialNum(lastReq);
        enc.lastResultSerialNum(lastRes);
        writeChecksummed(writer, checksum, MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength());
        return 1;
    }

    private int writeFooter(SnapshotWriter writer, int totalEntries, long checksum) {
        SnapshotFooterEncoder enc = new SnapshotFooterEncoder();
        MessageHeaderEncoder h = new MessageHeaderEncoder();
        enc.wrapAndApplyHeader(encodeBuffer, 0, h);
        enc.totalEntries(totalEntries + 1); // include self
        enc.checksum(checksum);
        writer.write(encodeBuffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + enc.encodedLength());
        return 1;
    }

    private void writeChecksummed(SnapshotWriter writer, SnapshotChecksum checksum, int length) {
        checksum.update(encodeBuffer, 0, length);
        writer.write(encodeBuffer, 0, length);
    }

    // ============ LOAD ============

    /**
     * 快照恢复返回值。
     */
    public static class LoadResult {
        public long maxProcessedRequestSerialNum;
        public long maxResultSerialNum;
        public int entryCount;
    }

    public LoadResult loadSnapshot(MatchEngineState state,
                                   SerialNumValidator validator,
                                   MatchResultEventsHelper eventsHelper,
                                   SnapshotReader reader) {
        LoadResult res = new LoadResult();
        Map<Integer, String> codeToSymbol = new HashMap<>();
        SnapshotChecksum checksum = new SnapshotChecksum();
        final boolean[] headerSeen = {false};
        final boolean[] footerSeen = {false};
        final int[] actualEntries = {0};
        final long[] expectedEntries = {0L};
        final long[] expectedChecksum = {0L};

        // 恢复过程中记录当前 orderBook 上下文
        final String[] currentSymbolId = {null};
        final OrderBook[] currentBook = {null};
        MessageHeaderDecoder hd = new MessageHeaderDecoder();

        while (reader.readOne((buffer, offset, length, header) -> {
            hd.wrap(buffer, offset);
            int templateId = hd.templateId();
            actualEntries[0]++;

            switch (templateId) {
                case SnapshotHeaderDecoder.TEMPLATE_ID -> {
                    headerSeen[0] = true;
                    checksum.update(buffer, offset, length);
                    SnapshotHeaderDecoder dec = new SnapshotHeaderDecoder();
                    dec.wrapAndApplyHeader(buffer, offset, hd);
                    res.maxProcessedRequestSerialNum = dec.maxProcessedRequestSerialNum();
                    res.maxResultSerialNum = dec.maxResultSerialNum();
                }
                case SymbolConfigEntryDecoder.TEMPLATE_ID -> {
                    checksum.update(buffer, offset, length);
                    SymbolConfigEntryDecoder dec = new SymbolConfigEntryDecoder();
                    dec.wrapAndApplyHeader(buffer, offset, hd);
                    int symbolCode = dec.symbolCode();
                    SymbolConfig cfg = new SymbolConfig();
                    cfg.setSymbolId(symbolCode);
                    cfg.setBaseCoinId((int) dec.baseCoinId());
                    cfg.setQuoteCoinId((int) dec.quoteCoinId());
                    String name = dec.symbolName();
                    cfg.setSymbolName(name);
                    cfg.setSymbolDisplayName(name);
                    state.getMatchContext().addSymbol(symbolCode, cfg);
                    codeToSymbol.put(symbolCode, name);

                    MatchConfig mc = new MatchConfig();
                    mc.setSymbol(name);
                    mc.setEnabled(dec.enabled().value() == 1);
                    long protectionBps = dec.actingVersion() >= 1
                            ? dec.marketOrderProtectionBps()
                            : defaultMarketOrderProtectionBps;
                    mc.setMarketOrderProtectionBps(MarketOrderConfig.normalizeProtectionBps(protectionBps));
                    state.addMatchConfig(mc);
                }
                case OrderBookStartDecoder.TEMPLATE_ID -> {
                    checksum.update(buffer, offset, length);
                    OrderBookStartDecoder dec = new OrderBookStartDecoder();
                    dec.wrapAndApplyHeader(buffer, offset, hd);

                    currentSymbolId[0] = dec.symbolId();
                    OrderBook book = new OrderBook(currentSymbolId[0]);

                    // 恢复订单簿
                    state.getMatchContext().addOrderBook(book);
                    currentBook[0] = book;
                }
                case MatchOrderEntryDecoder.TEMPLATE_ID -> {
                    checksum.update(buffer, offset, length);
                    MatchOrderEntryDecoder dec = new MatchOrderEntryDecoder();
                    dec.wrapAndApplyHeader(buffer, offset, hd);

                    MatchOrder o = decodeMatchOrder(dec, currentSymbolId[0]);
                    if (currentBook[0] != null) {
                        currentBook[0].addOrder(o);
                    }
                }
                case SymbolSerialNumEntryDecoder.TEMPLATE_ID -> {
                    checksum.update(buffer, offset, length);
                    // 暂时只读取，不需要特殊处理 (全局值已从 Header 恢复)
                }
                case SnapshotFooterDecoder.TEMPLATE_ID -> {
                    footerSeen[0] = true;
                    SnapshotFooterDecoder dec = new SnapshotFooterDecoder();
                    dec.wrapAndApplyHeader(buffer, offset, hd);
                    res.entryCount = (int) dec.totalEntries();
                    expectedEntries[0] = dec.totalEntries();
                    expectedChecksum[0] = dec.checksum();
                }
                default -> log.warn("[SnapshotManager] unknown templateId={}", templateId);
            }
        })) {
            // keep reading
        }

        validateSnapshotIntegrity(headerSeen[0], footerSeen[0], actualEntries[0],
                expectedEntries[0], checksum.value(), expectedChecksum[0]);

        // 恢复 serialNum 状态
        validator.restoreLastSerialNum(res.maxProcessedRequestSerialNum);

        eventsHelper.restoreNextResultSerialNum(res.maxResultSerialNum + 1);

        log.info("[SnapshotManager] loadSnapshot done, entries={}, maxReq={}, maxRes(snap)={}, nextRes={}",
                res.entryCount, res.maxProcessedRequestSerialNum, res.maxResultSerialNum, res.maxResultSerialNum + 1);
        return res;
    }

    private void validateSnapshotIntegrity(boolean headerSeen,
                                           boolean footerSeen,
                                           int actualEntries,
                                           long expectedEntries,
                                           long actualChecksum,
                                           long expectedChecksum) {
        if (!headerSeen) {
            throw new IllegalStateException("snapshot header missing");
        }
        if (!footerSeen) {
            throw new IllegalStateException("snapshot footer missing");
        }
        if (actualEntries != expectedEntries) {
            throw new IllegalStateException("snapshot entry count mismatch: actual="
                    + actualEntries + ", expected=" + expectedEntries);
        }
        if (actualChecksum != expectedChecksum) {
            throw new IllegalStateException("snapshot checksum mismatch: actual="
                    + actualChecksum + ", expected=" + expectedChecksum);
        }
    }

    private static final class SnapshotChecksum {
        private final CRC32C crc = new CRC32C();
        private byte[] scratch = new byte[8 * 1024];

        void update(org.agrona.DirectBuffer buffer, int offset, int length) {
            if (scratch.length < length) {
                scratch = new byte[length];
            }
            buffer.getBytes(offset, scratch, 0, length);
            crc.update(scratch, 0, length);
        }

        long value() {
            return crc.getValue();
        }
    }

    private MatchOrder decodeMatchOrder(MatchOrderEntryDecoder dec, String symbolId) {
        MatchOrder o = new MatchOrder();
        o.setOrderId(dec.orderId());
        o.setAccountId(dec.accountId());
        o.setSymbolId(symbolId);
        o.setOrderType(unmapOrderType(dec.orderType()));
        o.setOrderSide(unmapOrderSide(dec.orderSide()));
        o.setTimeInForce(unmapTif(dec.timeInForce()));
        o.setStpStrategyEnum(unmapStp(dec.stpStrategy()));
        o.setStpAccountId(dec.stpAccountId());
        o.setOrderStatus(unmapOrderStatus(dec.orderStatus()));
        o.setCreateTime(dec.createTime());
        o.setUpdateTime(dec.updateTime());
        o.setDelegatePrice(BigDecimalUtil.stringToBigDecimal(dec.delegatePrice()));
        o.setDelegateCount(BigDecimalUtil.stringToBigDecimal(dec.delegateCount()));
        String dealtStr = dec.dealtCount();
        o.setDealtCount(dealtStr.isEmpty() ? BigDecimal.ZERO : BigDecimalUtil.stringToBigDecimal(dealtStr));
        o.setClientOid(dec.clientOid());
        return o;
    }

    // ============ enum mappers ============

    private SnapOrderType mapOrderType(OrderType t) {
        if (t == null) return SnapOrderType.NULL_VAL;
        return t == OrderType.LIMIT ? SnapOrderType.LIMIT : SnapOrderType.MARKET;
    }

    private OrderType unmapOrderType(SnapOrderType t) {
        if (t == SnapOrderType.LIMIT) return OrderType.LIMIT;
        if (t == SnapOrderType.MARKET) return OrderType.MARKET;
        return null;
    }

    private SnapOrderSide mapOrderSide(OrderSideEnum s) {
        if (s == null) return SnapOrderSide.NULL_VAL;
        return s == OrderSideEnum.BUY ? SnapOrderSide.BUY : SnapOrderSide.SELL;
    }

    private OrderSideEnum unmapOrderSide(SnapOrderSide s) {
        if (s == SnapOrderSide.BUY) return OrderSideEnum.BUY;
        if (s == SnapOrderSide.SELL) return OrderSideEnum.SELL;
        return null;
    }

    private SnapTimeInForce mapTif(TimeInForceEnum t) {
        if (t == null) return SnapTimeInForce.NULL_VAL;
        return switch (t) {
            case GTC -> SnapTimeInForce.GTC;
            case IOC -> SnapTimeInForce.IOC;
            case FOK -> SnapTimeInForce.FOK;
            case POST_ONLY -> SnapTimeInForce.POST_ONLY;
        };
    }

    private TimeInForceEnum unmapTif(SnapTimeInForce t) {
        return switch (t) {
            case GTC -> TimeInForceEnum.GTC;
            case IOC -> TimeInForceEnum.IOC;
            case FOK -> TimeInForceEnum.FOK;
            case POST_ONLY -> TimeInForceEnum.POST_ONLY;
            default -> null;
        };
    }

    private SnapStpStrategy mapStp(StpStrategyEnum s) {
        if (s == null) return SnapStpStrategy.DEFAULT;
        return switch (s) {
            case DEFAULT -> SnapStpStrategy.DEFAULT;
            case CANCEL_MAKER -> SnapStpStrategy.CANCEL_MAKER;
            case CANCEL_TAKER -> SnapStpStrategy.CANCEL_TAKER;
            case CANCEL_BOTH -> SnapStpStrategy.CANCEL_BOTH;
        };
    }

    private StpStrategyEnum unmapStp(SnapStpStrategy s) {
        return switch (s) {
            case DEFAULT -> StpStrategyEnum.DEFAULT;
            case CANCEL_MAKER -> StpStrategyEnum.CANCEL_MAKER;
            case CANCEL_TAKER -> StpStrategyEnum.CANCEL_TAKER;
            case CANCEL_BOTH -> StpStrategyEnum.CANCEL_BOTH;
            default -> StpStrategyEnum.DEFAULT;
        };
    }

    private SnapOrderStatus mapOrderStatus(OrderStatusEnum s) {
        if (s == null) return SnapOrderStatus.NULL_VAL;
        return switch (s) {
            case NEW -> SnapOrderStatus.NEW;
            case CANCELLED -> SnapOrderStatus.CANCELLED;
            case PARTIALLY_FILLED -> SnapOrderStatus.PARTIALLY_FILLED;
            case FULL_FILLED -> SnapOrderStatus.FULL_FILLED;
            case REJECTED -> SnapOrderStatus.REJECTED;
        };
    }

    private OrderStatusEnum unmapOrderStatus(SnapOrderStatus s) {
        return switch (s) {
            case NEW -> OrderStatusEnum.NEW;
            case CANCELLED -> OrderStatusEnum.CANCELLED;
            case PARTIALLY_FILLED -> OrderStatusEnum.PARTIALLY_FILLED;
            case FULL_FILLED -> OrderStatusEnum.FULL_FILLED;
            case REJECTED -> OrderStatusEnum.REJECTED;
            default -> null;
        };
    }
}
