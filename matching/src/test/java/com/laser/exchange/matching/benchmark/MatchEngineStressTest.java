package com.laser.exchange.matching.benchmark;

import com.laser.exchange.matching.core.engine.MatchEngine;
import com.laser.exchange.matching.core.model.MatchConfig;
import com.laser.exchange.matching.core.model.MatchOrder;
import com.laser.exchange.common.enums.TimeInForceEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * MatchEngine 自定义压力测试 — 基于时间持续运行的稳态测量框架。
 *
 * <p>与 JMH Benchmark 的区别：</p>
 * <pre>
 *   ┌──────────────────────────────────────────────────────────┐
 *   │            JMH Benchmark vs 自定义压力测试               │
 *   ├──────────────┬──────────────────┬────────────────────────┤
 *   │              │  JMH Benchmark   │  MatchEngineStressTest │
 *   ├──────────────┼──────────────────┼────────────────────────┤
 *   │  目的        │  微观延迟精度    │  宏观吞吐 + 百分位     │
 *   │  百分位      │  仅 avg          │  TP50/90/99/99.9       │
 *   │  稳定性      │  多 fork         │  多轮持续运行          │
 *   │  输出        │  JSON/Console    │  ASCII 直方图 + 表格   │
 *   │  依赖        │  JMH 框架        │  零依赖（纯 Java）     │
 *   │  运行方式    │  Fork JVM        │  直接 main 方法        │
 *   └──────────────┴──────────────────┴────────────────────────┘
 * </pre>
 *
 * <h3>测量策略（统一纯操作计时模式）：</h3>
 * <pre>
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │  所有场景使用统一测量方法（可直接横向对比）：                  │
 *   │                                                              │
 *   │  ┌─────────────┐   ┌─────────────────────────────────────┐  │
 *   │  │ 预热阶段    │   │ 测量阶段                             │  │
 *   │  │ 持续 W 秒   │ → │ 第1轮(R秒) → 第2轮(R秒) → ... → 第N轮│  │
 *   │  │ (不记录)    │   │ 每轮独立统计吞吐量                   │  │
 *   │  └─────────────┘   └─────────────────────────────────────┘  │
 *   │                                                              │
 *   │  吞吐量 = totalOps / sumOfLatencies（纯 placeOrder 时间）   │
 *   │  引擎重建时间不计入吞吐量（消除重建开销的干扰）              │
 *   │  结果 = 各轮吞吐量均值 ± 标准差                              │
 *   │  延迟 = 所有轮次采样合并 → TP50/90/99/99.9                  │
 *   └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>运行方式：</h3>
 * <pre>
 *   // 默认配置（约 2.5 分钟）
 *   mvn test-compile exec:java \
 *       -Dexec.mainClass="com.laser.exchange.matching.benchmark.MatchEngineStressTest" \
 *       -Dexec.classpathScope="test"
 *
 *   // 快速验证（约 30 秒）
 *   mvn test-compile exec:java \
 *       -Dexec.mainClass="com.laser.exchange.matching.benchmark.MatchEngineStressTest" \
 *       -Dexec.classpathScope="test" \
 *       -Dstress.warmup.seconds=1 \
 *       -Dstress.round.seconds=1 \
 *       -Dstress.measurement.rounds=3
 *
 *   // 深度测试（约 10 分钟）
 *   mvn test-compile exec:java \
 *       -Dexec.mainClass="com.laser.exchange.matching.benchmark.MatchEngineStressTest" \
 *       -Dexec.classpathScope="test" \
 *       -Dstress.warmup.seconds=5 \
 *       -Dstress.round.seconds=5 \
 *       -Dstress.measurement.rounds=10
 * </pre>
 *
 * @see LatencyStats
 * @see ConsoleReporter
 */
public class MatchEngineStressTest {

    // ==================== 配置参数 ====================

    private final int prefillOrders;
    private final int priceLevels;
    private final int warmupSeconds;
    private final int roundSeconds;
    private final int measurementRounds;

    // ==================== 常量 ====================

    private static final double BASE_PRICE = 49000.0;
    private static final double PRICE_STEP = 1.0;
    private static final double DEFAULT_QTY = 1.0;

    /**
     * 每轮最大延迟采样数（限制内存使用）。
     * 1M 样本 × 8 字节 = 8MB/轮，足够计算精确的 TP99.9。
     */
    private static final int MAX_SAMPLES_PER_ROUND = 1_000_000;

    /**
     * 检查轮次时间截止的频率（每 256 次操作检查一次）。
     * 使用 2 的幂以便用位运算代替取模。
     */
    private static final int DEADLINE_CHECK_INTERVAL = 256;
    private static final int DEADLINE_CHECK_MASK = DEADLINE_CHECK_INTERVAL - 1;

    // ==================== 默认配置 ====================

    private static final int DEFAULT_PREFILL_ORDERS = 10000;
    private static final int DEFAULT_PRICE_LEVELS = 100;
    private static final int DEFAULT_WARMUP_SECONDS = 3;
    private static final int DEFAULT_ROUND_SECONDS = 3;
    private static final int DEFAULT_MEASUREMENT_ROUNDS = 5;

    // ==================== 场景定义 ====================

    /**
     * 场景配置。
     *
     * <p>所有场景统一使用「周期性引擎重建 + 纯操作计时」模式：</p>
     * <ul>
     *   <li>每执行 opsPerEngine 次操作后重建引擎（重建耗时不计入吞吐量）</li>
     *   <li>吞吐量 = totalOps / sumOfLatencies（纯 placeOrder 调用时间）</li>
     * </ul>
     *
     * @param name         场景名称
     * @param opsPerEngine 每次引擎重建前可执行的操作数
     * @param action       场景操作
     */
    record ScenarioConfig(
            String name,
            int opsPerEngine,
            Consumer<MatchEngine> action
    ) {}

    /**
     * 单轮测量结果。
     */
    record RoundMetric(
            int totalOps,
            long wallClockNanos,
            long[] latencySamples,
            int sampleCount
    ) {
        double throughputOpsPerSec() {
            return totalOps * 1_000_000_000.0 / wallClockNanos;
        }
    }

    /**
     * 场景测量结果。
     */
    record ScenarioResult(
            String name,
            List<RoundMetric> rounds,
            LatencyStats mergedStats
    ) {
        double avgThroughput() {
            return rounds.stream()
                    .mapToDouble(RoundMetric::throughputOpsPerSec)
                    .average().orElse(0);
        }

        double throughputStdDev() {
            double avg = avgThroughput();
            double variance = rounds.stream()
                    .mapToDouble(r -> {
                        double diff = r.throughputOpsPerSec() - avg;
                        return diff * diff;
                    })
                    .average().orElse(0);
            return Math.sqrt(variance);
        }

        double throughputErrorPct() {
            double avg = avgThroughput();
            return avg > 0 ? throughputStdDev() / avg * 100 : 0;
        }

        int totalOps() {
            return rounds.stream().mapToInt(RoundMetric::totalOps).sum();
        }
    }

    // ==================== 构造 ====================

    public MatchEngineStressTest(int prefillOrders, int priceLevels,
                                 int warmupSeconds, int roundSeconds,
                                 int measurementRounds) {
        this.prefillOrders = prefillOrders;
        this.priceLevels = priceLevels;
        this.warmupSeconds = warmupSeconds;
        this.roundSeconds = roundSeconds;
        this.measurementRounds = measurementRounds;
    }

    // ==================== 入口 ====================

    public static void main(String[] args) {
        int prefill = getIntProp("stress.prefill.orders", DEFAULT_PREFILL_ORDERS);
        int levels = getIntProp("stress.prefill.priceLevels", DEFAULT_PRICE_LEVELS);
        int warmup = getIntProp("stress.warmup.seconds", DEFAULT_WARMUP_SECONDS);
        int round = getIntProp("stress.round.seconds", DEFAULT_ROUND_SECONDS);
        int rounds = getIntProp("stress.measurement.rounds", DEFAULT_MEASUREMENT_ROUNDS);

        MatchEngineStressTest test = new MatchEngineStressTest(
                prefill, levels, warmup, round, rounds);
        test.run();
    }

    /**
     * 执行所有压力测试场景并输出结果。
     */
    public void run() {
        List<ScenarioConfig> scenarios = defineScenarios();

        ConsoleReporter.printHeader(prefillOrders, priceLevels,
                warmupSeconds, measurementRounds, roundSeconds);

        List<ScenarioResult> results = new ArrayList<>();
        long globalStart = System.nanoTime();

        for (int i = 0; i < scenarios.size(); i++) {
            ScenarioConfig sc = scenarios.get(i);
            ScenarioResult result = runScenario(i + 1, scenarios.size(), sc);
            results.add(result);
        }

        // 汇总表
        List<String> names = new ArrayList<>();
        List<Double> throughputs = new ArrayList<>();
        List<Double> errorPcts = new ArrayList<>();
        List<LatencyStats> statsList = new ArrayList<>();

        for (ScenarioResult r : results) {
            names.add(r.name());
            throughputs.add(r.avgThroughput());
            errorPcts.add(r.throughputErrorPct());
            statsList.add(r.mergedStats());
        }

        ConsoleReporter.printSummaryTable(names, throughputs, errorPcts, statsList);

        double totalSec = (System.nanoTime() - globalStart) / 1_000_000_000.0;
        ConsoleReporter.printFooter(totalSec);
    }

    // ==================== 场景运行 ====================

    /**
     * 运行单个场景的完整测试流程：预热 → N 轮测量 → 输出结果。
     *
     * <pre>
     *   ┌───────────┐     ┌───────────────────────────────────────────────┐
     *   │ 预热阶段  │     │ 测量阶段                                      │
     *   │ W 秒持续  │ →  │ 轮次1: R秒 → 轮次2: R秒 → ... → 轮次N: R秒   │
     *   │ (JIT预热) │     │ 每轮独立统计吞吐 + 采样延迟                    │
     *   └───────────┘     └──────────────────────┬────────────────────────┘
     *                                            │
     *                                   ┌────────▼────────┐
     *                                   │ 合并所有轮次延迟 │
     *                                   │ 计算 TP50/90/99 │
     *                                   │ 均值吞吐 ± 误差% │
     *                                   └─────────────────┘
     * </pre>
     */
    private ScenarioResult runScenario(int index, int total,
                                       ScenarioConfig sc) {
        // ── 预热阶段 ──
        ConsoleReporter.printPhaseStart(index, total, sc.name(),
                "预热", warmupSeconds);

        runTimed(sc, warmupSeconds, false);

        System.gc(); // 预热结束后 GC，减少测量期间的 GC 干扰

        // ── 测量阶段（N 轮） ──
        ConsoleReporter.printPhaseStart(index, total, sc.name(),
                "测量", measurementRounds * roundSeconds);

        List<RoundMetric> rounds = new ArrayList<>();
        for (int r = 0; r < measurementRounds; r++) {
            RoundMetric metric = runTimed(sc, roundSeconds, true);
            rounds.add(metric);
            ConsoleReporter.printRoundResult(
                    r + 1, metric.totalOps(), metric.throughputOpsPerSec());
        }

        // 合并所有轮次的延迟采样
        long[][] allSamples = new long[rounds.size()][];
        int[] allCounts = new int[rounds.size()];
        for (int i = 0; i < rounds.size(); i++) {
            allSamples[i] = rounds.get(i).latencySamples();
            allCounts[i] = rounds.get(i).sampleCount();
        }
        LatencyStats merged = LatencyStats.merge(allSamples, allCounts);

        ScenarioResult result = new ScenarioResult(sc.name(), rounds, merged);

        ConsoleReporter.printScenarioResult(
                index, total, sc.name(),
                result.avgThroughput(), result.throughputErrorPct(),
                result.totalOps(), merged);

        return result;
    }

    // ==================== 持续运行核心逻辑 ====================

    /**
     * 统一测量方法 — 所有场景使用相同的「周期性引擎重建 + 纯操作计时」模式。
     *
     * <p>核心思路：</p>
     * <ul>
     *   <li>每执行 opsPerEngine 次操作后重建引擎（重建耗时不计入吞吐量）</li>
     *   <li>吞吐量 = totalOps / totalOperationNanos（纯 placeOrder 调用时间）</li>
     *   <li>外层循环使用挂钟时间控制测试时长，避免重建开销导致实际运行时间远超预期</li>
     * </ul>
     *
     * <pre>
     *   ┌─────────────────────────────────────────────────┐
     *   │  while (wallClock < duration):                  │
     *   │    重建引擎（不计时）                             │
     *   │    for i in [0, opsPerEngine):                   │
     *   │      计时 placeOrder → 累加纯操作耗时            │
     *   │  吞吐量 = totalOps / totalOperationNanos         │
     *   └─────────────────────────────────────────────────┘
     * </pre>
     */
    private RoundMetric runTimed(ScenarioConfig sc,
                                 int durationSeconds,
                                 boolean recordLatency) {
        int opsPerEngine = sc.opsPerEngine();
        long durationNanos = durationSeconds * 1_000_000_000L;

        long[] samples = recordLatency ? new long[MAX_SAMPLES_PER_ROUND] : null;
        int sampleCount = 0;
        int totalOps = 0;
        long totalOperationNanos = 0; // 纯操作时间（不含引擎重建）

        long wallStart = System.nanoTime();
        long wallDeadline = wallStart + durationNanos;

        while (System.nanoTime() < wallDeadline) {
            // 重建引擎（不计时）
            OrderGenerator.resetSequence();
            MatchEngine engine = createEngine();
            prefillOrderBook(engine);

            for (int i = 0; i < opsPerEngine; i++) {
                long opStart = System.nanoTime();
                sc.action().accept(engine);
                long elapsed = System.nanoTime() - opStart;

                totalOperationNanos += elapsed;
                if (recordLatency && sampleCount < MAX_SAMPLES_PER_ROUND) {
                    samples[sampleCount++] = elapsed;
                }
                totalOps++;

                // 每 256 次操作检查一次挂钟是否超时
                if ((totalOps & DEADLINE_CHECK_MASK) == 0) {
                    if (System.nanoTime() >= wallDeadline) break;
                }
            }
        }

        return new RoundMetric(totalOps, totalOperationNanos,
                samples != null ? samples : new long[0], sampleCount);
    }

    // ==================== 场景定义 ====================

    /**
     * 定义 9 个测试场景。
     *
     * <p>所有场景使用统一的「周期性引擎重建 + 纯操作计时」模式。
     * opsPerEngine 含义：每批次可执行的最大操作数，超过后重建引擎。</p>
     *
     * <pre>
     *   ┌──────────────────────────────────────────────────────────────┐
     *   │  场景                          │ opsPerEngine │  复杂度     │
     *   ├────────────────────────────────┼──────────────┼─────────────┤
     *   │  1. GTC 不交叉挂单             │  prefill     │  O(logN)    │
     *   │  2. GTC 单档成交               │  askOrders   │  O(1)       │
     *   │  3. GTC 多档位成交             │  levels/10   │  O(N*M)     │
     *   │  4. IOC 立即成交               │  askOrders   │  O(1)       │
     *   │  5. FOK 完全成交               │  askOrders   │  O(1)+预撮合│
     *   │  6. FOK 撤单                   │  prefill     │  O(N*M)     │
     *   │  7. POST_ONLY 挂单             │  prefill     │  O(logN)    │
     *   │  8. 深度订单簿扫盘             │  1           │  O(N*M)     │
     *   │  9. 混合场景                   │  askOrders/2 │  随机        │
     *   └──────────────────────────────────────────────────────────────┘
     * </pre>
     */
    private List<ScenarioConfig> defineScenarios() {
        double askBestPrice = BASE_PRICE + priceLevels + PRICE_STEP;
        int ordersPerLevel = Math.max(1, (prefillOrders / 2) / priceLevels);
        int totalAskOrders = prefillOrders / 2;

        int levelsToSweep = 10;
        double sweepPrice = askBestPrice + (levelsToSweep - 1) * PRICE_STEP;
        double multiLevelQty = ordersPerLevel * levelsToSweep * DEFAULT_QTY;
        int opsPerEngineMultiLevel = Math.max(1, priceLevels / levelsToSweep);

        double impossibleQty = prefillOrders * 10.0;
        double fokCancelPrice = askBestPrice + priceLevels * PRICE_STEP;

        double askTopPrice = askBestPrice + (priceLevels - 1) * PRICE_STEP;
        double totalAskQty = totalAskOrders * DEFAULT_QTY;

        return List.of(
                // 场景1: 不交叉 → 订单累积到买盘，与成交场景统一批次大小
                new ScenarioConfig("GTC 不交叉挂单", totalAskOrders,
                        engine -> engine.placeOrder(
                                OrderGenerator.limitBuy(BASE_PRICE - 500,
                                        DEFAULT_QTY, TimeInForceEnum.GTC))),

                // 场景2: 每笔吃掉1个ask → 耗尽后重建
                new ScenarioConfig("GTC 单档成交", totalAskOrders,
                        engine -> engine.placeOrder(
                                OrderGenerator.limitBuy(askBestPrice,
                                        DEFAULT_QTY, TimeInForceEnum.GTC))),

                // 场景3: 一笔横扫10档 → 快速消耗深度
                new ScenarioConfig("GTC 多档位成交", opsPerEngineMultiLevel,
                        engine -> engine.placeOrder(
                                OrderGenerator.limitBuy(sweepPrice,
                                        multiLevelQty, TimeInForceEnum.GTC))),

                // 场景4: IOC 立即成交或撤销
                new ScenarioConfig("IOC 立即成交", totalAskOrders,
                        engine -> engine.placeOrder(
                                OrderGenerator.limitBuy(askBestPrice,
                                        DEFAULT_QTY, TimeInForceEnum.IOC))),

                // 场景5: FOK 全额成交
                new ScenarioConfig("FOK 完全成交", totalAskOrders,
                        engine -> engine.placeOrder(
                                OrderGenerator.limitBuy(askBestPrice,
                                        DEFAULT_QTY, TimeInForceEnum.FOK))),

                // 场景6: FOK 无法全部成交 → 直接撤单，订单簿不变
                new ScenarioConfig("FOK 撤单", totalAskOrders,
                        engine -> engine.placeOrder(
                                OrderGenerator.limitBuy(fokCancelPrice,
                                        impossibleQty, TimeInForceEnum.FOK))),

                // 场景7: POST_ONLY 挂单不成交，订单累积，统一批次大小
                new ScenarioConfig("POST_ONLY 挂单", totalAskOrders,
                        engine -> engine.placeOrder(
                                OrderGenerator.limitBuy(BASE_PRICE - 500,
                                        DEFAULT_QTY, TimeInForceEnum.POST_ONLY))),

                // 场景8: 一笔清空整个ask深度
                new ScenarioConfig("深度订单簿扫盘", 1,
                        engine -> engine.placeOrder(
                                OrderGenerator.limitBuy(askTopPrice + 100,
                                        totalAskQty, TimeInForceEnum.GTC))),

                // 场景9: 随机混合多种操作
                new ScenarioConfig("混合场景", totalAskOrders / 2,
                        engine -> {
                            int type = ThreadLocalRandom.current().nextInt(4);
                            MatchOrder order = switch (type) {
                                case 0 -> OrderGenerator.limitBuy(
                                        BASE_PRICE - 200, DEFAULT_QTY,
                                        TimeInForceEnum.GTC);
                                case 1 -> OrderGenerator.limitBuy(
                                        askBestPrice, DEFAULT_QTY,
                                        TimeInForceEnum.GTC);
                                case 2 -> OrderGenerator.limitBuy(
                                        askBestPrice, DEFAULT_QTY,
                                        TimeInForceEnum.IOC);
                                default -> OrderGenerator.limitBuy(
                                        BASE_PRICE - 200, DEFAULT_QTY,
                                        TimeInForceEnum.POST_ONLY);
                            };
                            engine.placeOrder(order);
                        })
        );
    }

    // ==================== 引擎工具方法 ====================

    private MatchEngine createEngine() {
        MatchEngine engine = new MatchEngine();
        MatchConfig matchConfig = new MatchConfig();
        matchConfig.setSymbol(OrderGenerator.SYMBOL);
        matchConfig.setEnabled(true);
        matchConfig.setMarketOrderHangingTime(5000);
        engine.getMatchEngineState().addMatchConfig(matchConfig);
        return engine;
    }

    private void prefillOrderBook(MatchEngine engine) {
        int halfOrders = prefillOrders / 2;
        int ordersPerLevel = Math.max(1, halfOrders / priceLevels);

        for (int level = 0; level < priceLevels; level++) {
            double buyPrice = BASE_PRICE - level * PRICE_STEP;
            for (int j = 0; j < ordersPerLevel; j++) {
                engine.placeOrder(OrderGenerator.limitBuy(
                        buyPrice, DEFAULT_QTY, TimeInForceEnum.GTC));
            }
        }

        double askBasePrice = BASE_PRICE + priceLevels + PRICE_STEP;
        for (int level = 0; level < priceLevels; level++) {
            double sellPrice = askBasePrice + level * PRICE_STEP;
            for (int j = 0; j < ordersPerLevel; j++) {
                engine.placeOrder(OrderGenerator.limitSell(
                        sellPrice, DEFAULT_QTY, TimeInForceEnum.GTC));
            }
        }
    }

    // ==================== 配置读取 ====================

    private static int getIntProp(String key, int defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            System.err.printf("[StressTest] 无法解析 %s=%s，使用默认值 %d%n",
                    key, val, defaultValue);
            return defaultValue;
        }
    }
}
