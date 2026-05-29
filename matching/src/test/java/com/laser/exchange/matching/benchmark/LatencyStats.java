package com.laser.exchange.matching.benchmark;

import java.util.Arrays;

/**
 * 延迟统计工具 — 从原始纳秒延迟数组中计算百分位、直方图等指标。
 *
 * <p>核心算法：将所有延迟值排序后，通过数组下标直接定位百分位值，
 * 时间复杂度 O(N log N)（排序），空间复杂度 O(N)。</p>
 *
 * <pre>
 *   ┌────────────────────────────────────────────────────────────┐
 *   │  输入: long[] rawLatencies (纳秒)                          │
 *   │                                                            │
 *   │  ┌──────────┐    ┌──────────┐    ┌──────────────────────┐  │
 *   │  │ 排序     │ →  │ 百分位   │ →  │ TP50/90/99/99.9     │  │
 *   │  │ O(NlogN) │    │ O(1)查询 │    │ min/max/avg          │  │
 *   │  └──────────┘    └──────────┘    └──────────────────────┘  │
 *   │                                                            │
 *   │  ┌──────────────────────────────────────────────────────┐  │
 *   │  │ 直方图: 将延迟值分桶统计，用于分布可视化              │  │
 *   │  └──────────────────────────────────────────────────────┘  │
 *   └────────────────────────────────────────────────────────────┘
 * </pre>
 */
public class LatencyStats {

    /** 排序后的延迟数组（纳秒） */
    private final long[] sorted;

    /** 所有延迟之和（纳秒） */
    private final long sumNanos;

    /**
     * 从原始纳秒延迟数组构造统计对象。
     *
     * @param rawLatencies 每次操作的延迟（纳秒），数组会被拷贝后排序
     * @param count        有效元素个数（rawLatencies 可能有尾部空位）
     */
    public LatencyStats(long[] rawLatencies, int count) {
        this.sorted = Arrays.copyOf(rawLatencies, count);
        Arrays.sort(this.sorted);
        long s = 0;
        for (long l : sorted) {
            s += l;
        }
        this.sumNanos = s;
    }

    /** 样本数量 */
    public int count() {
        return sorted.length;
    }

    /** 平均延迟（微秒） */
    public double avgUs() {
        if (sorted.length == 0) return 0;
        return (sumNanos / (double) sorted.length) / 1000.0;
    }

    /**
     * 计算指定百分位的延迟值（微秒）。
     *
     * <p>使用 ceiling 方式定位：p=99 表示排序后第 ceil(0.99*N) 个值。</p>
     *
     * @param percentile 百分位，取值 0-100（支持小数如 99.9）
     * @return 该百分位对应的延迟（微秒）
     */
    public double percentileUs(double percentile) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx] / 1000.0;
    }

    public double tp50Us() { return percentileUs(50); }

    public double tp90Us() { return percentileUs(90); }

    public double tp99Us() { return percentileUs(99); }

    public double tp999Us() { return percentileUs(99.9); }

    public double minUs() { return sorted.length > 0 ? sorted[0] / 1000.0 : 0; }

    public double maxUs() { return sorted.length > 0 ? sorted[sorted.length - 1] / 1000.0 : 0; }

    /** 延迟之和（纳秒） */
    public long sumNanos() {
        return sumNanos;
    }

    /**
     * 计算延迟直方图 — 将延迟值按桶上界分组计数。
     *
     * <p>返回数组长度 = bucketUpperBoundsUs.length + 1，
     * 最后一个元素是超过最大桶上界的计数。</p>
     *
     * @param bucketUpperBoundsUs 桶上界数组（微秒），如 {1, 5, 10, 50, 100}
     * @return 每个桶的计数
     */
    public int[] histogram(double[] bucketUpperBoundsUs) {
        int[] counts = new int[bucketUpperBoundsUs.length + 1];
        for (long latencyNs : sorted) {
            double us = latencyNs / 1000.0;
            boolean placed = false;
            for (int b = 0; b < bucketUpperBoundsUs.length; b++) {
                if (us <= bucketUpperBoundsUs[b]) {
                    counts[b]++;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                counts[bucketUpperBoundsUs.length]++;
            }
        }
        return counts;
    }

    /**
     * 合并多个延迟数组为一个 LatencyStats。
     *
     * @param arrays 多个原始延迟数组
     * @param counts 每个数组的有效元素个数
     * @return 合并后的统计对象
     */
    public static LatencyStats merge(long[][] arrays, int[] counts) {
        int total = 0;
        for (int c : counts) total += c;
        long[] merged = new long[total];
        int offset = 0;
        for (int i = 0; i < arrays.length; i++) {
            System.arraycopy(arrays[i], 0, merged, offset, counts[i]);
            offset += counts[i];
        }
        return new LatencyStats(merged, total);
    }
}
