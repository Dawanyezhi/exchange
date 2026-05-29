package com.laser.exchange.counter.service;

import com.laser.exchange.common.PlaceOrderRequest;
import com.laser.exchange.common.enums.OrderSideEnum;
import com.laser.exchange.common.enums.OrderType;
import com.laser.exchange.common.enums.StpStrategyEnum;
import com.laser.exchange.common.enums.TimeInForceEnum;
import com.laser.exchange.counter.client.AeronClusterClientService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class BatchOrderService {

    @Resource
    private AeronClusterClientService clusterClient;

    @Resource
    private RequestSerialNumGenerator serialNumGenerator;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private volatile long startTimeMs;
    private volatile int currentRate;
    private volatile int currentSymbolCode;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> batchFuture;
    private final MutableDirectBuffer encodeBuffer = new ExpandableArrayBuffer(512);

    private final AtomicLong orderIdSequence = new AtomicLong(System.currentTimeMillis());

    /**
     * Start batch order sending.
     *
     * @param rate       orders per second
     * @param symbolCode target symbol code
     * @param basePrice  base price around which orders are generated
     * @param quantity   order quantity
     */
    public void start(int rate, int symbolCode, BigDecimal basePrice, BigDecimal quantity) {
        if (running.getAndSet(true)) {
            log.warn("Batch order is already running, stop it first");
            return;
        }

        totalSent.set(0);
        totalFailed.set(0);
        startTimeMs = System.currentTimeMillis();
        currentRate = rate;
        currentSymbolCode = symbolCode;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "batch-order-sender");
            t.setDaemon(true);
            return t;
        });

        long periodMicros = 1_000_000L / rate;

        batchFuture = scheduler.scheduleAtFixedRate(
                () -> sendOneOrder(symbolCode, basePrice, quantity),
                0, periodMicros, TimeUnit.MICROSECONDS);

        log.info("Batch order started: rate={}/s, symbolCode={}, basePrice={}, qty={}",
                rate, symbolCode, basePrice, quantity);
    }

    /**
     * Stop batch order sending and return statistics.
     */
    public Map<String, Object> stop() {
        Map<String, Object> stats = getStatus();
        if (running.getAndSet(false)) {
            if (batchFuture != null) {
                batchFuture.cancel(false);
            }
            if (scheduler != null) {
                scheduler.shutdown();
            }
            log.info("Batch order stopped: totalSent={}, totalFailed={}", totalSent.get(), totalFailed.get());
        }
        return stats;
    }

    /**
     * Get current batch order status.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", running.get());
        status.put("totalSent", totalSent.get());
        status.put("totalFailed", totalFailed.get());
        if (running.get() && startTimeMs > 0) {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            status.put("elapsedMs", elapsed);
            status.put("configuredRate", currentRate);
            status.put("symbolCode", currentSymbolCode);
            if (elapsed > 0) {
                double actualRate = totalSent.get() * 1000.0 / elapsed;
                status.put("actualRate", BigDecimal.valueOf(actualRate).setScale(2, RoundingMode.HALF_UP));
            }
        }
        return status;
    }

    /**
     * Generate and send one order.
     * 50% BUY / 50% SELL, 10% probability of cross (buy price >= sell price).
     */
    private void sendOneOrder(int symbolCode, BigDecimal basePrice, BigDecimal quantity) {
        try {
            boolean isBuy = Math.random() < 0.5;
            boolean isCross = Math.random() < 0.1;

            BigDecimal price;
            if (isCross) {
                // Cross order: price that will match (buy high / sell low)
                BigDecimal spread = basePrice.multiply(BigDecimal.valueOf(0.001));
                price = isBuy
                        ? basePrice.add(spread)    // buy above base → crosses with sell at base
                        : basePrice.subtract(spread); // sell below base → crosses with buy at base
            } else {
                // Normal order: price with small random offset away from base
                BigDecimal offset = basePrice.multiply(
                        BigDecimal.valueOf(0.001 + Math.random() * 0.01));
                price = isBuy
                        ? basePrice.subtract(offset)  // buy below base
                        : basePrice.add(offset);       // sell above base
            }
            price = price.setScale(2, RoundingMode.HALF_UP);

            PlaceOrderRequest request = PlaceOrderRequest.builder()
                    .serialNum(serialNumGenerator.next())
                    .orderId(orderIdSequence.incrementAndGet())
                    .clientOid("batch-" + totalSent.get())
                    .accountId(10001L)
                    .symbolCode(symbolCode)
                    .orderType(OrderType.LIMIT)
                    .orderSide(isBuy ? OrderSideEnum.BUY : OrderSideEnum.SELL)
                    .timeInForce(TimeInForceEnum.GTC)
                    .delegatePrice(price)
                    .delegateCount(quantity)
                    .stpAccountId(10001L)
                    .stpStrategyEnum(StpStrategyEnum.DEFAULT)
                    .build();

            int length = request.encode(encodeBuffer, 0);
            boolean success = clusterClient.offer(encodeBuffer, 0, length);

            if (success) {
                totalSent.incrementAndGet();
                if (log.isDebugEnabled()) {
                    log.debug("batch send: serialNum={}, orderId={}", request.getSerialNum(), request.getOrderId());
                }
            } else {
                totalFailed.incrementAndGet();
            }
        } catch (Exception e) {
            totalFailed.incrementAndGet();
            log.error("Batch order send error", e);
        }
    }
}
