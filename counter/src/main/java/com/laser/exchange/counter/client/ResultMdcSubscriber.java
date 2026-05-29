package com.laser.exchange.counter.client;

import com.laser.exchange.common.codec.CancelOrderResultDecoder;
import com.laser.exchange.common.codec.MatchOrderResultDecoder;
import com.laser.exchange.common.codec.MessageHeaderDecoder;
import com.laser.exchange.common.codec.PlaceOrderResultDecoder;
import com.laser.exchange.common.codec.TradeSwitchResultDecoder;
import com.laser.exchange.common.codec.UpDownSymbolResultDecoder;
import com.laser.exchange.common.result.CancelOrderResult;
import com.laser.exchange.common.result.MatchOrderResult;
import com.laser.exchange.common.result.MatchResult;
import com.laser.exchange.common.result.PlaceOrderResult;
import com.laser.exchange.common.result.TradeSwitchResult;
import com.laser.exchange.common.result.UpDownSymbolResult;
import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订阅服务端 MDC 广播的 MatchResult。
 *
 * <p><b>3 节点拓扑</b>：每个 server 节点在 base-port+nodeId 上开 MDC publication，
 * 但只有 LEADER 实际发数据。客户端并行订阅全部 3 个端口，LEADER 在哪收在哪。
 */
@Slf4j
@Component
public class ResultMdcSubscriber {

    @Resource
    private AeronClusterClientService clusterClientService;

    @Value("${laser.matching.result-broadcast.host:localhost}")
    private String host;

    @Value("${laser.matching.result-broadcast.base-port:40456}")
    private int basePort;

    @Value("${laser.matching.result-broadcast.node-count:3}")
    private int nodeCount;

    @Value("${laser.matching.result-broadcast.subscriber-base-port:40500}")
    private int subscriberBasePort;

    @Value("${laser.matching.result-broadcast.stream-id:1002}")
    private int streamId;

    @Value("${laser.matching.result-broadcast.subscribe-enabled:true}")
    private boolean enabled;

    private final List<Subscription> subscriptions = new ArrayList<>();
    private Thread pollThread;
    private volatile boolean running = false;

    private final AtomicLong received = new AtomicLong(0);

    private final FragmentHandler handler = new FragmentAssembler(this::handleFragment);

    private final SleepingMillisIdleStrategy idle = new SleepingMillisIdleStrategy(1);

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final PlaceOrderResult placeOrderResult = new PlaceOrderResult();
    private final MatchOrderResult matchOrderResult = new MatchOrderResult();
    private final CancelOrderResult cancelOrderResult = new CancelOrderResult();
    private final UpDownSymbolResult upDownSymbolResult = new UpDownSymbolResult();
    private final TradeSwitchResult tradeSwitchResult = new TradeSwitchResult();


    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("[ResultMdcSubscriber] disabled");
            return;
        }
        pollThread = new Thread(this::runLoop, "mdc-result-subscriber");
        pollThread.setDaemon(true);
        running = true;
        pollThread.start();
        log.info("[ResultMdcSubscriber] started, subscribing 3 nodes on basePort={}", basePort);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        for (Subscription s : subscriptions) {
            try {
                CloseHelper.close(s);
            } catch (Exception ignored) {
            }
        }
        log.info("[ResultMdcSubscriber] stopped, totalReceived={}", received.get());
    }

    private void runLoop() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            return;
        }

        Aeron aeron = clusterClientService.getAeronOrNull();
        if (aeron == null) {
            log.error("[ResultMdcSubscriber] Aeron unavailable, giving up");
            return;
        }

        // 并行订阅全部节点的 MDC 端口
        for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
            int controlPort = basePort + nodeId;
            int subPort = subscriberBasePort + nodeId;
            String channel = "aeron:udp?endpoint=" + host + ":" + subPort + "|control=" + host + ":" + controlPort;
            Subscription sub = aeron.addSubscription(channel, streamId);
            subscriptions.add(sub);
            log.info("[ResultMdcSubscriber] subscribed nodeId={}, channel={}, regId={}", nodeId, channel, sub.registrationId());
        }

        while (running && !Thread.currentThread().isInterrupted()) {
            int fragments = 0;
            for (Subscription s : subscriptions) {
                fragments += s.poll(handler, 32);
            }
            idle.idle(fragments);
        }
    }


    private void handleFragment(DirectBuffer buffer, int offset, int length,
                                Header header) {
        headerDecoder.wrap(buffer, offset);
        int templateId = headerDecoder.templateId();
        long n = received.incrementAndGet();

        MatchResult result = decodeResult(buffer, offset, templateId);
        if (result == null) {
            log.warn("[MDC-RESULT #{}] unknown templateId={}", n, templateId);
            return;
        }

        log.info("[MDC-RESULT #{}] {}", n, result);
    }

    private MatchResult decodeResult(DirectBuffer buffer, int offset, int templateId) {
        return switch (templateId) {
            case PlaceOrderResultDecoder.TEMPLATE_ID -> placeOrderResult.decode(buffer, offset);
            case MatchOrderResultDecoder.TEMPLATE_ID -> matchOrderResult.decode(buffer, offset);
            case CancelOrderResultDecoder.TEMPLATE_ID -> cancelOrderResult.decode(buffer, offset);
            case UpDownSymbolResultDecoder.TEMPLATE_ID -> upDownSymbolResult.decode(buffer, offset);
            case TradeSwitchResultDecoder.TEMPLATE_ID -> tradeSwitchResult.decode(buffer, offset);
            default -> null;
        };
    }

    public long getReceivedCount() {
        return received.get();
    }
}
