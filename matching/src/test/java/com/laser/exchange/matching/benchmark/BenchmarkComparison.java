package com.laser.exchange.matching.benchmark;

import com.laser.exchange.common.enums.*;
import com.laser.exchange.matching.core.engine.MatchEngine;
import com.laser.exchange.matching.core.engine.MatchEngineV1;
import com.laser.exchange.matching.core.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * V0/V1 Benchmark 对比运行器
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>每个场景预热引擎一次，测量阶段不重建引擎</li>
 *   <li>撮合场景用 maker+taker 配对，撮合后 book 自动清空（无需手动清理）</li>
 *   <li>稳态场景用 place+cancel 配对，保持 book 大小恒定</li>
 * </ul>
 *
 * <pre>
 * 运行方式:
 *   mvn test-compile exec:java \
 *       -Dexec.mainClass="com.laser.exchange.matching.benchmark.BenchmarkComparison" \
 *       -Dexec.classpathScope="test"
 * </pre>
 */
public class BenchmarkComparison {

    private static final int WARMUP_OPS = 100_000;
    private static final int MEASURE_OPS = 500_000;
    private static final String SYMBOL = "SPOT_BTC_USDT";

    record BenchResult(String scenario, String version, int ops, long nanos) {
        double throughputMOps() { return ops * 1000.0 / nanos; }
        double latencyNs() { return (double) nanos / ops; }
    }

    public static void main(String[] args) {
        List<BenchResult> results = new ArrayList<>();

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║         Laser Matching Engine — V0/V1 Benchmark             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // 1. 纯下单（累积，无撤单）
        results.add(runV0Accumulate());
        results.add(runV1Accumulate());

        // 2. 纯撤单（预填充后逐一撤单）
        results.add(runV0PureCancel());
        results.add(runV1PureCancel());

        // 3. 下单+撤单 1:1 稳态
        results.add(runV0SteadyState("PlaceCancelSteadyState", TimeInForceEnum.GTC));
        results.add(runV1SteadyState("PlaceCancelSteadyState", TimeInForceEnum.GTC));

        // 4. PostOnly 未交叉下单（稳态 place+cancel）
        results.add(runV0SteadyState("PostOnlyNoMatch", TimeInForceEnum.POST_ONLY));
        results.add(runV1SteadyState("PostOnlyNoMatch", TimeInForceEnum.POST_ONLY));

        // 5. FOK 交叉撤单（FOK fail 场景）
        results.add(runV0FokFail());
        results.add(runV1FokFail());

        // 6. GTC 撮合（全成交）
        results.add(runV0Match("GtcMatch", TimeInForceEnum.GTC, 100));
        results.add(runV1Match("GtcMatch", TimeInForceEnum.GTC, 100));

        // 7. IOC 撮合（全成交）
        results.add(runV0Match("IocMatch", TimeInForceEnum.IOC, 100));
        results.add(runV1Match("IocMatch", TimeInForceEnum.IOC, 100));

        // 8. FOK 撮合（全成交）
        results.add(runV0Match("FokMatchSuccess", TimeInForceEnum.FOK, 100));
        results.add(runV1Match("FokMatchSuccess", TimeInForceEnum.FOK, 100));

        printComparison(results);

        printComparison(results);
    }

    // ====================== 稳态模式: place + cancel ======================

    private static BenchResult runV0SteadyState(String name, TimeInForceEnum tif) {
        System.out.print("  V0 " + name + " ...");
        MatchEngine engine = createV0Engine();
        OrderGenerator.resetSequence();
        // 预填充
        for (int i = 0; i < 1000; i++) {
            engine.placeOrder(OrderGenerator.limitBuy(48000 + (i % 100), 1, TimeInForceEnum.GTC));
        }
        // 预热
        for (int i = 0; i < WARMUP_OPS; i++) {
            MatchOrder o = OrderGenerator.limitBuy(1000, 1, tif);
            engine.placeOrder(o);
            engine.cancelOrder(o.getOrderId(), SYMBOL);
        }
        // 测量
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            MatchOrder o = OrderGenerator.limitBuy(1000, 1, tif);
            engine.placeOrder(o);
            engine.cancelOrder(o.getOrderId(), SYMBOL);
        }
        long elapsed = System.nanoTime() - start;
        double mops = MEASURE_OPS * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult(name, "V0", MEASURE_OPS, elapsed);
    }

    private static BenchResult runV1SteadyState(String name, TimeInForceEnum tif) {
        System.out.print("  V1 " + name + " ...");
        MatchEngineV1 engine = createV1Engine();
        OrderGeneratorV1.resetSequence();
        for (int i = 0; i < 1000; i++) {
            engine.placeOrder(OrderGeneratorV1.limitBuy(48000 + (i % 100), 1, TimeInForceEnum.GTC));
        }
        for (int i = 0; i < WARMUP_OPS; i++) {
            MatchOrderV1 o = OrderGeneratorV1.limitBuy(1000, 1, tif);
            engine.placeOrder(o);
            engine.cancelOrder(o.getOrderId(), SYMBOL);
        }
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            MatchOrderV1 o = OrderGeneratorV1.limitBuy(1000, 1, tif);
            engine.placeOrder(o);
            engine.cancelOrder(o.getOrderId(), SYMBOL);
        }
        long elapsed = System.nanoTime() - start;
        double mops = MEASURE_OPS * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult(name, "V1", MEASURE_OPS, elapsed);
    }

    // ====================== 撮合模式: maker + taker 配对（单引擎复用） ======================

    private static BenchResult runV0Match(String name, TimeInForceEnum takerTif, int takerQty) {
        System.out.print("  V0 " + name + " ...");
        MatchEngine engine = createV0Engine();
        OrderGenerator.resetSequence();
        // 预热（同一引擎，maker+taker 配对，撮合后 book 自动清空）
        for (int i = 0; i < WARMUP_OPS; i++) {
            engine.placeOrder(OrderGenerator.limitSell(100, 100, TimeInForceEnum.GTC));
            engine.placeOrder(OrderGenerator.limitBuy(100, takerQty, takerTif));
        }
        // 测量
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            engine.placeOrder(OrderGenerator.limitSell(100, 100, TimeInForceEnum.GTC));
            engine.placeOrder(OrderGenerator.limitBuy(100, takerQty, takerTif));
        }
        long elapsed = System.nanoTime() - start;
        double mops = MEASURE_OPS * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult(name, "V0", MEASURE_OPS, elapsed);
    }

    private static BenchResult runV1Match(String name, TimeInForceEnum takerTif, int takerQty) {
        System.out.print("  V1 " + name + " ...");
        MatchEngineV1 engine = createV1Engine();
        OrderGeneratorV1.resetSequence();
        for (int i = 0; i < WARMUP_OPS; i++) {
            engine.placeOrder(OrderGeneratorV1.limitSell(100, 100, TimeInForceEnum.GTC));
            engine.placeOrder(OrderGeneratorV1.limitBuy(100, takerQty, takerTif));
        }
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            engine.placeOrder(OrderGeneratorV1.limitSell(100, 100, TimeInForceEnum.GTC));
            engine.placeOrder(OrderGeneratorV1.limitBuy(100, takerQty, takerTif));
        }
        long elapsed = System.nanoTime() - start;
        double mops = MEASURE_OPS * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult(name, "V1", MEASURE_OPS, elapsed);
    }

    // ====================== 纯撤单（预填充后逐一撤单）======================

    private static BenchResult runV0PureCancel() {
        System.out.print("  V0 PureCancel ...");
        MatchEngine engine = createV0Engine();
        OrderGenerator.resetSequence();
        // 预填充 MEASURE_OPS 个订单
        long[] ids = new long[MEASURE_OPS];
        for (int i = 0; i < MEASURE_OPS; i++) {
            MatchOrder o = OrderGenerator.limitBuy(48000 + (i % 100), 1, TimeInForceEnum.GTC);
            engine.placeOrder(o);
            ids[i] = o.getOrderId();
        }
        // 预热（撤后重下）
        for (int i = 0; i < Math.min(WARMUP_OPS, MEASURE_OPS); i++) {
            engine.cancelOrder(ids[i], SYMBOL);
        }
        // 重建填充
        engine = createV0Engine();
        OrderGenerator.resetSequence();
        for (int i = 0; i < MEASURE_OPS; i++) {
            MatchOrder o = OrderGenerator.limitBuy(48000 + (i % 100), 1, TimeInForceEnum.GTC);
            engine.placeOrder(o);
            ids[i] = o.getOrderId();
        }
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            engine.cancelOrder(ids[i], SYMBOL);
        }
        long elapsed = System.nanoTime() - start;
        double mops = MEASURE_OPS * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult("PureCancel", "V0", MEASURE_OPS, elapsed);
    }

    private static BenchResult runV1PureCancel() {
        System.out.print("  V1 PureCancel ...");
        MatchEngineV1 engine = createV1Engine();
        OrderGeneratorV1.resetSequence();
        long[] ids = new long[MEASURE_OPS];
        for (int i = 0; i < MEASURE_OPS; i++) {
            MatchOrderV1 o = OrderGeneratorV1.limitBuy(48000 + (i % 100), 1, TimeInForceEnum.GTC);
            engine.placeOrder(o);
            ids[i] = o.getOrderId();
        }
        for (int i = 0; i < Math.min(WARMUP_OPS, MEASURE_OPS); i++) {
            engine.cancelOrder(ids[i], SYMBOL);
        }
        engine = createV1Engine();
        OrderGeneratorV1.resetSequence();
        for (int i = 0; i < MEASURE_OPS; i++) {
            MatchOrderV1 o = OrderGeneratorV1.limitBuy(48000 + (i % 100), 1, TimeInForceEnum.GTC);
            engine.placeOrder(o);
            ids[i] = o.getOrderId();
        }
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            engine.cancelOrder(ids[i], SYMBOL);
        }
        long elapsed = System.nanoTime() - start;
        double mops = MEASURE_OPS * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult("PureCancel", "V1", MEASURE_OPS, elapsed);
    }

    // ====================== FOK 失败场景（maker 不够）======================

    private static BenchResult runV0FokFail() {
        System.out.print("  V0 FokMatchFail ...");
        MatchEngine engine = createV0Engine();
        OrderGenerator.resetSequence();
        // 预填充一个不够的 maker
        engine.placeOrder(OrderGenerator.limitSell(100, 50, TimeInForceEnum.GTC));
        // 预热
        for (int i = 0; i < WARMUP_OPS; i++) {
            engine.placeOrder(OrderGenerator.limitBuy(100, 100, TimeInForceEnum.FOK)); // 会被撤
        }
        // 测量
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            engine.placeOrder(OrderGenerator.limitBuy(100, 100, TimeInForceEnum.FOK));
        }
        long elapsed = System.nanoTime() - start;
        double mops = MEASURE_OPS * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult("FokMatchFail", "V0", MEASURE_OPS, elapsed);
    }

    private static BenchResult runV1FokFail() {
        System.out.print("  V1 FokMatchFail ...");
        MatchEngineV1 engine = createV1Engine();
        OrderGeneratorV1.resetSequence();
        engine.placeOrder(OrderGeneratorV1.limitSell(100, 50, TimeInForceEnum.GTC));
        for (int i = 0; i < WARMUP_OPS; i++) {
            engine.placeOrder(OrderGeneratorV1.limitBuy(100, 100, TimeInForceEnum.FOK));
        }
        long start = System.nanoTime();
        for (int i = 0; i < MEASURE_OPS; i++) {
            engine.placeOrder(OrderGeneratorV1.limitBuy(100, 100, TimeInForceEnum.FOK));
        }
        long elapsed = System.nanoTime() - start;
        double mops = MEASURE_OPS * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult("FokMatchFail", "V1", MEASURE_OPS, elapsed);
    }

    // ====================== 累积模式（book 不断增大）======================

    private static BenchResult runV0Accumulate() {
        System.out.print("  V0 PlaceOrderNoMatch ...");
        MatchEngine engine = createV0Engine();
        OrderGenerator.resetSequence();
        for (int i = 0; i < WARMUP_OPS; i++) {
            engine.placeOrder(OrderGenerator.limitBuy(1000 + (i % 100), 1, TimeInForceEnum.GTC));
        }
        engine = createV0Engine();
        OrderGenerator.resetSequence();
        int ops = MEASURE_OPS;
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            engine.placeOrder(OrderGenerator.limitBuy(1000 + (i % 100), 1, TimeInForceEnum.GTC));
        }
        long elapsed = System.nanoTime() - start;
        double mops = ops * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult("PlaceOrderNoMatch", "V0", ops, elapsed);
    }

    private static BenchResult runV1Accumulate() {
        System.out.print("  V1 PlaceOrderNoMatch ...");
        MatchEngineV1 engine = createV1Engine();
        OrderGeneratorV1.resetSequence();
        for (int i = 0; i < WARMUP_OPS; i++) {
            engine.placeOrder(OrderGeneratorV1.limitBuy(1000 + (i % 100), 1, TimeInForceEnum.GTC));
        }
        engine = createV1Engine();
        OrderGeneratorV1.resetSequence();
        int ops = MEASURE_OPS;
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            engine.placeOrder(OrderGeneratorV1.limitBuy(1000 + (i % 100), 1, TimeInForceEnum.GTC));
        }
        long elapsed = System.nanoTime() - start;
        double mops = ops * 1000.0 / elapsed;
        System.out.printf(" %.2f M ops/s%n", mops);
        return new BenchResult("PlaceOrderNoMatch", "V1", ops, elapsed);
    }

    // ====================== 引擎工厂 ======================

    private static MatchEngine createV0Engine() {
        MatchEngine engine = new MatchEngine();
        MatchConfig config = new MatchConfig();
        config.setSymbol(SYMBOL);
        config.setEnabled(true);
        engine.getMatchEngineState().addMatchConfig(config);
        return engine;
    }

    private static MatchEngineV1 createV1Engine() {
        MatchEngineV1 engine = new MatchEngineV1();
        engine.initDefaultConfig(SYMBOL);
        return engine;
    }

    // ====================== 输出 ======================

    private static void printComparison(List<BenchResult> results) {
        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                     V0 vs V1 Performance Comparison                      ║");
        System.out.println("╠═══════════════════════╦══════════════╦══════════════╦══════════════════════╣");
        System.out.printf( "║ %-21s ║ %-12s ║ %-12s ║ %-20s ║%n", "Scenario", "V0 (M ops/s)", "V1 (M ops/s)", "Speedup");
        System.out.println("╠═══════════════════════╬══════════════╬══════════════╬══════════════════════╣");

        for (int i = 0; i < results.size(); i += 2) {
            BenchResult v0 = results.get(i);
            BenchResult v1 = results.get(i + 1);
            double speedup = v1.throughputMOps() / v0.throughputMOps();
            String bar = "█".repeat(Math.min(Math.max((int)(speedup * 5), 1), 15));
            System.out.printf("║ %-21s ║ %10.2f   ║ %10.2f   ║ %5.2fx %s%n",
                v0.scenario(), v0.throughputMOps(), v1.throughputMOps(), speedup, bar);
        }

        System.out.println("╚═══════════════════════╩══════════════╩══════════════╩══════════════════════╝");
    }
}
