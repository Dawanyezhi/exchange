package com.laser.exchange.resultpublisher.service;

import com.laser.exchange.resultpublisher.archive.ArchiveResultLogReader;
import com.laser.exchange.resultpublisher.archive.ResultLogEntry;
import com.laser.exchange.resultpublisher.archive.ResultLogScanState;
import com.laser.exchange.resultpublisher.checkpoint.ResultPublisherCheckpoint;
import com.laser.exchange.resultpublisher.config.ResultPublisherProperties;
import com.laser.exchange.resultpublisher.publish.ResultPublisher;
import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;
import org.agrona.CloseHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
public class ArchiveResultConsumerService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ArchiveResultConsumerService.class);

    private final ResultPublisherProperties properties;

    private final ResultPublisher publisher;

    private final ResultPublisherCheckpoint checkpoint;

    private volatile boolean running;

    private volatile RuntimeException startupFailure;

    private CountDownLatch startupLatch;

    private Thread workerThread;

    public ArchiveResultConsumerService(ResultPublisherProperties properties,
                                        ResultPublisher publisher,
                                        ResultPublisherCheckpoint checkpoint) {
        this.properties = properties;
        this.publisher = publisher;
        this.checkpoint = checkpoint;
    }

    @Override
    public synchronized void start() {
        if (!properties.isEnabled()) {
            log.info("[ArchiveResultConsumerService] disabled");
            return;
        }
        if (running) {
            return;
        }

        running = true;
        startupFailure = null;
        startupLatch = new CountDownLatch(1);
        workerThread = new Thread(this::consumeLoop, "result-publisher-archive-consumer");
        workerThread.setDaemon(true);
        workerThread.start();
        awaitStartup();
        log.info("[ArchiveResultConsumerService] started");
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(properties.getRetryIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
        log.info("[ArchiveResultConsumerService] stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void consumeLoop() {
        long startupDeadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getInitialConnectTimeoutMs());
        long retryIntervalMs = properties.getRetryIntervalMs();
        boolean startupCompleted = false;

        while (running) {
            Aeron aeron = null;
            AeronArchive archive = null;
            try {
                aeron = connectAeron();
                archive = connectArchive(aeron);
                ArchiveResultLogReader reader = new ArchiveResultLogReader(archive, properties.toReaderConfig());
                ResultLogScanState state = new ResultLogScanState(checkpoint.lastResultSerialNum());

                // 开始replay位置
                long startPosition = checkpoint.nextReplayPosition();

                log.info("[ArchiveResultConsumerService] following result recording from position={}, lastResultSerialNum={}, channel={}, streamId={}",
                        startPosition, state.getLastResultSerialNum(), properties.getResultChannel(), properties.getResultStreamId());

                startupCompleted = true;
                signalStartupSuccess();

                // 拉取结果
                reader.followFrom(startPosition, state, this::publishAndCheckpoint, () -> running);
            } catch (Exception e) {
                if (running) {
                    if (!startupCompleted && shouldFailStartup(startupDeadlineNs, retryIntervalMs)) {
                        failStartup("result-publisher failed to connect Archive before startup timeout", e);
                        return;
                    }

                    log.error("[ArchiveResultConsumerService] consume loop failed, retryIntervalMs={}", retryIntervalMs, e);
                    sleepBeforeRetry(retryIntervalMs);
                    retryIntervalMs = nextRetryIntervalMs(retryIntervalMs);
                }
            } finally {
                CloseHelper.quietClose(archive);
                CloseHelper.quietClose(aeron);
            }
        }
        signalStartupSuccess();
    }

    protected Aeron connectAeron() {
        Aeron.Context context = new Aeron.Context()
                .aeronDirectoryName(properties.aeronDirectoryName())
                .driverTimeoutMs(properties.getAeronDriverTimeoutMs())
                .clientName("result-publisher-" + properties.getNodeId());

        log.info("[ArchiveResultConsumerService] connecting aeron, aeronDir={}", properties.aeronDirectoryName());
        return Aeron.connect(context);
    }

    protected AeronArchive connectArchive(Aeron aeron) {
        AeronArchive.Context context = new AeronArchive.Context()
                .aeron(aeron)
                .ownsAeronClient(false)
                .controlRequestChannel(properties.archiveControlChannel())
                .messageTimeoutNs(properties.getArchiveMessageTimeoutMs() * 1_000_000L)
                .clientName("result-publisher-" + properties.getNodeId());

        log.info("[ArchiveResultConsumerService] connecting archive, aeronDir={}, archiveControlChannel={}",
                properties.aeronDirectoryName(), properties.archiveControlChannel());
        return AeronArchive.connect(context);
    }

    void publishAndCheckpoint(ResultLogEntry entry) {
        publisher.publish(entry);
        checkpoint.markPublished(entry);
    }

    private void awaitStartup() {
        try {
            boolean completed = startupLatch.await(
                    properties.getInitialConnectTimeoutMs() + properties.getRetryMaxIntervalMs(),
                    TimeUnit.MILLISECONDS);
            if (!completed) {
                stop();
                throw new IllegalStateException("result-publisher startup did not complete before timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("interrupted while waiting result-publisher startup", e);
        }

        if (startupFailure != null) {
            stop();
            throw startupFailure;
        }
    }

    private boolean shouldFailStartup(long startupDeadlineNs, long currentRetryIntervalMs) {
        return currentRetryIntervalMs >= properties.getRetryMaxIntervalMs()
                || System.nanoTime() >= startupDeadlineNs;
    }

    private void failStartup(String message, Exception cause) {
        startupFailure = new IllegalStateException(message, cause);
        running = false;
        signalStartupSuccess();
    }

    private void signalStartupSuccess() {
        CountDownLatch latch = startupLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    private long nextRetryIntervalMs(long currentRetryIntervalMs) {
        long max = Math.max(1L, properties.getRetryMaxIntervalMs());
        if (currentRetryIntervalMs >= max) {
            return max;
        }
        long doubled = currentRetryIntervalMs <= Long.MAX_VALUE / 2 ? currentRetryIntervalMs * 2 : max;
        return Math.min(max, Math.max(1L, doubled));
    }

    private void sleepBeforeRetry(long retryIntervalMs) {
        try {
            Thread.sleep(retryIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
