package com.laser.exchange.counter.controller;

import com.laser.exchange.common.AmendOrderRequest;
import com.laser.exchange.common.CancelOrderRequest;
import com.laser.exchange.common.PlaceOrderRequest;
import com.laser.exchange.counter.service.BatchOrderService;
import com.laser.exchange.counter.service.OrderService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
public class OrderController {

    @Resource
    private OrderService orderService;

    @Resource
    private BatchOrderService batchOrderService;

    @PostMapping("/api/order/place")
    public Map<String, Object> placeOrder(@RequestBody PlaceOrderRequest request) {
        boolean success = orderService.placeOrder(request);
        return buildResult(success, "placeOrder");
    }

    @PostMapping("/api/order/cancel")
    public Map<String, Object> cancelOrder(@RequestBody CancelOrderRequest request) {
        boolean success = orderService.cancelOrder(request);
        return buildResult(success, "cancelOrder");
    }

    @PostMapping("/api/order/amend")
    public Map<String, Object> amendOrder(@RequestBody AmendOrderRequest request) {
        boolean success = orderService.amendOrder(request);
        return buildResult(success, "amendOrder");
    }

    @PostMapping("/api/order/batch/start")
    public Map<String, Object> batchStart(
            @RequestParam(name = "rate", defaultValue = "100") int rate,
            @RequestParam(name = "symbolCode", defaultValue = "1") int symbolCode,
            @RequestParam(name = "basePrice", defaultValue = "50000.00") BigDecimal basePrice,
            @RequestParam(name = "quantity", defaultValue = "1.5") BigDecimal quantity) {
        batchOrderService.start(rate, symbolCode, basePrice, quantity);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Batch order started");
        result.put("rate", rate);
        result.put("symbolCode", symbolCode);
        result.put("basePrice", basePrice);
        result.put("quantity", quantity);
        return result;
    }

    @PostMapping("/api/order/batch/stop")
    public Map<String, Object> batchStop() {
        Map<String, Object> stats = batchOrderService.stop();
        stats.put("success", true);
        stats.put("message", "Batch order stopped");
        return stats;
    }

    @GetMapping("/api/order/batch/status")
    public Map<String, Object> batchStatus() {
        return batchOrderService.getStatus();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("application", "exchange-counter");
        return result;
    }

    private Map<String, Object> buildResult(boolean success, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("action", action);
        return result;
    }
}
