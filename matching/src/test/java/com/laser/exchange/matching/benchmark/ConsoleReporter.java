package com.laser.exchange.matching.benchmark;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 控制台报告输出器 — 以 ASCII Art 格式美化输出压力测试结果。
 *
 * <p>输出内容包括：</p>
 * <ul>
 *   <li>测试配置信息头部</li>
 *   <li>每个场景的逐轮吞吐量、百分位延迟、直方图</li>
 *   <li>所有场景的汇总对比表格</li>
 * </ul>
 */
public class ConsoleReporter {

    private static final int WIDTH = 66;
    private static final String DOUBLE_LINE = "=".repeat(WIDTH);
    private static final String SINGLE_LINE = "-".repeat(WIDTH);
    private static final String THICK_LINE = repeat('━', WIDTH);
    private static final String THIN_LINE = repeat('─', 42);

    private static final String FULL_BLOCK = "█";
    private static final int MAX_BAR_WIDTH = 36;

    /**
     * 打印报告头部：测试配置信息。
     */
    public static void printHeader(int prefillOrders, int priceLevels,
                                   int warmupSeconds, int measurementRounds,
                                   int roundSeconds) {
        System.out.println();
        System.out.println(DOUBLE_LINE);
        System.out.println("  Laser Matching Engine — 压力测试报告");
        System.out.println(DOUBLE_LINE);
        String time = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.out.println("  测试时间:     " + time);
        System.out.printf("  预填充订单:   %,d%n", prefillOrders);
        System.out.printf("  价格档位数:   %d%n", priceLevels);
        System.out.printf("  预热时间:     %d 秒%n", warmupSeconds);
        System.out.printf("  测量轮次:     %d 轮 × %d 秒/轮%n",
                measurementRounds, roundSeconds);
        System.out.println(DOUBLE_LINE);
        System.out.println();
    }

    /**
     * 打印场景运行进度。
     */
    public static void printPhaseStart(int index, int total,
                                       String name, String phase,
                                       int seconds) {
        System.out.printf("  [%d/%d] %s — %s (%ds)...%n",
                index, total, name, phase, seconds);
    }

    /**
     * 打印单轮测量的吞吐量。
     */
    public static void printRoundResult(int round, int totalOps,
                                        double throughput) {
        System.out.printf("         轮次 %d:  %,12.0f ops/sec  (%,d ops)%n",
                round, throughput, totalOps);
    }

    /**
     * 打印单个场景的详细结果。
     */
    public static void printScenarioResult(int index, int total, String name,
                                           double avgThroughput,
                                           double errorPct,
                                           int totalOps,
                                           LatencyStats stats) {
        System.out.println();
        System.out.println(THICK_LINE);
        System.out.printf("  场景 %d/%d: %s%n", index, total, name);
        System.out.println(THICK_LINE);

        System.out.printf("  平均吞吐:  %,12.0f ops/sec (±%.1f%%)%n",
                avgThroughput, errorPct);
        System.out.printf("  总操作数:  %,d%n", totalOps);
        System.out.println("  " + THIN_LINE);
        System.out.printf("  平均延迟:  %12.3f us%n", stats.avgUs());
        System.out.printf("  TP50:      %12.3f us%n", stats.tp50Us());
        System.out.printf("  TP90:      %12.3f us%n", stats.tp90Us());
        System.out.printf("  TP99:      %12.3f us%n", stats.tp99Us());
        System.out.printf("  TP99.9:    %12.3f us%n", stats.tp999Us());
        System.out.printf("  最小值:    %12.3f us%n", stats.minUs());
        System.out.printf("  最大值:    %12.3f us%n", stats.maxUs());
        System.out.println("  " + THIN_LINE);

        printHistogram(stats);
        System.out.println();
    }

    /**
     * 打印延迟分布直方图。
     */
    private static void printHistogram(LatencyStats stats) {
        System.out.println("  延迟分布:");

        double maxUs = stats.maxUs();
        double[] buckets;
        String[] labels;

        if (maxUs <= 10) {
            buckets = new double[]{0.5, 1, 2, 5, 10};
            labels = new String[]{
                    "   <0.5us", " 0.5-1 us", "   1-2 us",
                    "   2-5 us", "  5-10 us", "  >10  us"
            };
        } else if (maxUs <= 100) {
            buckets = new double[]{1, 5, 10, 50, 100};
            labels = new String[]{
                    "   < 1 us", "   1-5 us", "  5-10 us",
                    " 10-50 us", "50-100 us", " >100  us"
            };
        } else {
            buckets = new double[]{10, 50, 100, 500, 1000};
            labels = new String[]{
                    "  < 10 us", " 10-50 us", "50-100 us",
                    "100-500us", "0.5-1  ms", "  > 1  ms"
            };
        }

        int[] counts = stats.histogram(buckets);
        int total = stats.count();
        int maxCount = 0;
        for (int c : counts) maxCount = Math.max(maxCount, c);

        for (int i = 0; i < counts.length; i++) {
            double pct = 100.0 * counts[i] / total;
            int barLen = maxCount > 0
                    ? (int) (MAX_BAR_WIDTH * counts[i] / (double) maxCount)
                    : 0;
            String bar = FULL_BLOCK.repeat(barLen);
            if (barLen == 0 && counts[i] > 0) bar = "▎";
            System.out.printf("    %s  %-36s %6.1f%%%n", labels[i], bar, pct);
        }
    }

    /**
     * 打印所有场景的汇总对比表格。
     */
    public static void printSummaryTable(List<String> names,
                                         List<Double> throughputs,
                                         List<Double> errorPcts,
                                         List<LatencyStats> statsList) {
        System.out.println();
        System.out.println(DOUBLE_LINE);
        System.out.println("  汇总对比");
        System.out.println(DOUBLE_LINE);
        System.out.println();

        System.out.printf("  %-3s %-26s %13s %7s %10s %10s %10s%n",
                "#", "场景", "吞吐(ops/s)", "误差",
                "平均(us)", "TP90(us)", "TP99(us)");
        System.out.println("  " + SINGLE_LINE);

        for (int i = 0; i < names.size(); i++) {
            LatencyStats s = statsList.get(i);
            String displayName = names.get(i);
            if (displayWidth(displayName) > 24) {
                displayName = truncateToWidth(displayName, 24);
            }
            System.out.printf("  %-3d %s %,13.0f %5.1f%% %10.3f %10.3f %10.3f%n",
                    i + 1,
                    padRight(displayName, 26),
                    throughputs.get(i),
                    errorPcts.get(i),
                    s.avgUs(),
                    s.tp90Us(),
                    s.tp99Us());
        }

        System.out.println("  " + SINGLE_LINE);
        printPerformanceRanking(names, throughputs, statsList);
    }

    /**
     * 打印按 TP99 延迟排序的性能排名。
     */
    private static void printPerformanceRanking(List<String> names,
                                                List<Double> throughputs,
                                                List<LatencyStats> statsList) {
        System.out.println();
        System.out.println("  性能排名 (按 TP99 延迟从低到高):");
        System.out.println("  " + SINGLE_LINE);

        Integer[] indices = new Integer[names.size()];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        java.util.Arrays.sort(indices,
                (a, b) -> Double.compare(
                        statsList.get(a).tp99Us(),
                        statsList.get(b).tp99Us()));

        for (int rank = 0; rank < indices.length; rank++) {
            int idx = indices[rank];
            LatencyStats s = statsList.get(idx);
            String medal = switch (rank) {
                case 0 -> " >> ";
                case 1 -> "  > ";
                default -> "    ";
            };
            System.out.printf(
                    "  %s#%d %-24s TP99=%8.3f us  |  %,.0f ops/s%n",
                    medal, idx + 1,
                    truncateToWidth(names.get(idx), 22),
                    s.tp99Us(),
                    throughputs.get(idx));
        }
        System.out.println();
    }

    /**
     * 打印测试完成信息。
     */
    public static void printFooter(double totalTimeSec) {
        System.out.println(DOUBLE_LINE);
        System.out.printf("  压力测试完成! 总耗时: %.1f 秒%n", totalTimeSec);
        System.out.println(DOUBLE_LINE);
        System.out.println();
    }

    // ==================== 字符串工具方法 ====================

    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            width += (s.charAt(i) > 0x7F) ? 2 : 1;
        }
        return width;
    }

    private static String padRight(String s, int targetWidth) {
        int padding = targetWidth - displayWidth(s);
        return padding <= 0 ? s : s + " ".repeat(padding);
    }

    private static String truncateToWidth(String s, int maxWidth) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            int cw = (s.charAt(i) > 0x7F) ? 2 : 1;
            if (width + cw > maxWidth) return s.substring(0, i);
            width += cw;
        }
        return s;
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }
}
