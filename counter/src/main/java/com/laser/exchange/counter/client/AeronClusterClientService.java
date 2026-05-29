package com.laser.exchange.counter.client;

import com.laser.exchange.counter.config.ClusterClientConfig;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.SleepingIdleStrategy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Aeron Cluster 客户端服务
 *
 * 核心设计：
 * - MediaDriver.launch() 固定目录 + SHARED 线程模型，启动一次永久复用
 * - 初始连接在 @PostConstruct 中同步完成，连接失败则终止 Spring 启动
 * - 独立心跳线程 (ScheduledExecutorService) 每3秒 sendKeepAlive + pollEgress
 * - 断线重连：心跳失败时标记断连，下次 offer 时触发 ensureConnected → reconnect
 * - 指数退避重连策略
 *
 * todo AeronClusterClientService重连机制
 */
@Slf4j
@Component
public class AeronClusterClientService {

    // ======================== 重连配置 ========================
    /** 最大重连尝试次数 */
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    /** 首次重连等待时间（毫秒） */
    private static final long INITIAL_RECONNECT_DELAY_MS = 1_000;

    /** 最大重连等待时间（毫秒），指数退避的上限 */
    private static final long MAX_RECONNECT_DELAY_MS = 30_000;

    /** 连续失败阈值，达到后触发重连 */
    private static final int CONSECUTIVE_FAILURE_THRESHOLD = 2;

    /** 心跳间隔（秒），需小于服务端 10 秒会话超时 */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 3;

    @Resource
    private ClusterClientConfig config;

    // ======================== Aeron 资源 ========================
    /** 媒体驱动，固定目录，启动一次永久复用 */
    private MediaDriver mediaDriver;

    /** 集群客户端连接，volatile 保证多线程可见性 */
    private volatile AeronCluster aeronCluster;

    // ======================== 状态管理 ========================
    /** 是否已关闭（Spring 销毁时置 true） */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 当前是否已连接到集群 */
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    /** 连续失败计数器，达到阈值后触发重连 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    /** 重连锁，防止多线程并发重连 */
    private final ReentrantLock reconnectLock = new ReentrantLock();
    /** 背压重试时的空闲策略 */
    private final SleepingIdleStrategy idleStrategy = new SleepingIdleStrategy(1);
    /** 心跳定时器 */
    private ScheduledExecutorService heartbeatExecutor;

    /** Egress 监听器，处理集群返回的消息 */
    private final EgressListener egressListener = (clusterSessionId, timestamp,
            buffer, offset, length, header) ->
        log.debug("Egress received: clusterSessionId={}, length={}", clusterSessionId, length);

    // ======================== 生命周期 ========================

    @PostConstruct
    void init() {
        // 1. 启动 MediaDriver（固定目录 + SHARED 线程模型，启动一次永久复用）
        String aeronDir = config.getBaseDir() + File.separator + "aeron";
        mediaDriver = MediaDriver.launch(new MediaDriver.Context()
                .aeronDirectoryName(aeronDir)
                .threadingMode(ThreadingMode.DEDICATED)
                .dirDeleteOnStart(false)
                .dirDeleteOnShutdown(false));
        log.info("MediaDriver launched: dir={}, threadingMode=SHARED", aeronDir);

        // 2. 启动心跳定时器 — 通过 sendKeepAlive + pollEgress 维持会话活跃
        heartbeatExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "aeron-client-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!closed.get() && isConnected.get()) {
                    aeronCluster.sendKeepAlive();
                    aeronCluster.pollEgress();
                }
            } catch (Exception e) {
                log.warn("Heartbeat keep-alive failed, connection may be lost: {}", e.getMessage());
                isConnected.set(false);
                consecutiveFailures.incrementAndGet();
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Heartbeat thread started (interval={}s)", HEARTBEAT_INTERVAL_SECONDS);

        // 3. 同步连接集群，失败则终止 Spring 启动
        aeronCluster = connectToCluster();
        log.info("Initial cluster connection successful");
    }

    @PreDestroy
    void shutdown() {
        log.info("Shutting down AeronClusterClient...");
        closed.set(true);

        // 关闭心跳定时器
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
            try {
                if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                heartbeatExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 关闭集群连接
        CloseHelper.quietClose(aeronCluster);
        // 关闭媒体驱动
        CloseHelper.quietClose(mediaDriver);
        log.info("AeronClusterClient shutdown complete");
    }

    // ======================== 连接管理 ========================

    /**
     * 连接到集群，复用已有的 MediaDriver
     */
    private AeronCluster connectToCluster() {
        log.info("Connecting to cluster: {}", config.getIngressEndpoints());

        AeronCluster.Context ctx = new AeronCluster.Context()
                .aeronDirectoryName(mediaDriver.aeronDirectoryName())
                .ingressChannel("aeron:udp")
                .ingressEndpoints(config.getIngressEndpoints())
                .egressChannel(config.getEgressChannel())
                .egressListener(egressListener);

        AeronCluster cluster = AeronCluster.connect(ctx);
        isConnected.set(true);
        consecutiveFailures.set(0);
        log.info("AeronCluster connected: ingressEndpoints={}", config.getIngressEndpoints());
        return cluster;
    }

    /**
     * 指数退避重连（由 ensureConnected 调用）
     */
    private void reconnect() {
        if (closed.get()) {
            return;
        }

        reconnectLock.lock();
        try {
            // 双重检查：其他线程可能已完成重连
            if (isConnected.get()) {
                return;
            }

            log.warn("Attempting to reconnect to cluster...");

            // 关闭旧连接
            if (aeronCluster != null) {
                try {
                    CloseHelper.quietClose(aeronCluster);
                } catch (Exception e) {
                    log.warn("Error closing old cluster connection: {}", e.getMessage());
                }
            }

            // 指数退避重试
            long delay = INITIAL_RECONNECT_DELAY_MS;
            for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
                try {
                    log.info("Reconnection attempt {}/{}", attempt, MAX_RECONNECT_ATTEMPTS);
                    aeronCluster = connectToCluster();
                    log.info("Reconnected to cluster on attempt {}", attempt);
                    return;
                } catch (Exception e) {
                    log.warn("Reconnection attempt {}/{} failed: {}", attempt, MAX_RECONNECT_ATTEMPTS, e.getMessage());

                    if (attempt < MAX_RECONNECT_ATTEMPTS) {
                        try {
                            log.info("Waiting {}ms before next attempt...", delay);
                            Thread.sleep(delay);
                            delay = Math.min(delay * 2, MAX_RECONNECT_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }

            log.error("Failed to reconnect after {} attempts", MAX_RECONNECT_ATTEMPTS);
            isConnected.set(false);
        } finally {
            reconnectLock.unlock();
        }
    }

    /**
     * 确保连接可用，必要时触发重连
     */
    private void ensureConnected() {
        if (closed.get()) {
            return;
        }

        // 连续失败次数达到阈值，标记为断连
        if (consecutiveFailures.get() >= CONSECUTIVE_FAILURE_THRESHOLD) {
            log.warn("Detected {} consecutive failures, triggering reconnection", consecutiveFailures.get());
            isConnected.set(false);
        }

        if (!isConnected.get()) {
            reconnect();
        }
    }

    // ======================== 公共接口 ========================

    /**
     * 向 Aeron Cluster 发送 SBE 编码的消息
     *
     * @param buffer SBE 编码后的消息缓冲区
     * @param offset 消息起始偏移量
     * @param length 消息长度
     * @return 发送成功返回 true，失败返回 false
     */
    public boolean offer(DirectBuffer buffer, int offset, int length) {
        ensureConnected();

        if (!isConnected.get() || aeronCluster == null) {
            log.warn("AeronCluster is not connected, cannot offer message");
            return false;
        }

        // 背压重试（最多 10 次）
        int attempts = 0;
        while (aeronCluster.offer(buffer, offset, length) < 0) {
            if (++attempts > 10) {
                log.warn("Failed to offer message after {} attempts", attempts);
                consecutiveFailures.incrementAndGet();
                return false;
            }
            aeronCluster.pollEgress();
            idleStrategy.idle();
        }

        // 发送成功，刷新 Egress
        aeronCluster.pollEgress();
        consecutiveFailures.set(0);
        return true;
    }

    /**
     * 检查集群客户端是否已连接且就绪
     */
    public boolean isConnected() {
        return isConnected.get() && aeronCluster != null;
    }

    /**
     * 获取底层 Aeron 实例，供 MDC 订阅方复用同一 MediaDriver。
     * cluster 未连上时返回 null。
     */
    public io.aeron.Aeron getAeronOrNull() {
        if (aeronCluster == null) {
            return null;
        }
        return aeronCluster.context().aeron();
    }

}
