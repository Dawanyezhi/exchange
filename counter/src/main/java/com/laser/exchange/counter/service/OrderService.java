package com.laser.exchange.counter.service;

import com.laser.exchange.common.AmendOrderRequest;
import com.laser.exchange.common.CancelOrderRequest;
import com.laser.exchange.common.PlaceOrderRequest;
import com.laser.exchange.counter.client.AeronClusterClientService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderService {

    @Resource
    private AeronClusterClientService clusterClient;

    @Resource
    private RequestSerialNumGenerator serialNumGenerator;

    private final MutableDirectBuffer encodeBuffer = new ExpandableArrayBuffer(512);

    public boolean placeOrder(PlaceOrderRequest request) {
        // 客户端统一在发送前分配 serialNum，确保全局严格 +1
        request.setSerialNum(serialNumGenerator.next());
        int length = request.encode(encodeBuffer, 0);
        boolean success = clusterClient.offer(encodeBuffer, 0, length);
        log.info("placeOrder: serialNum={}, orderId={}, symbolCode={}, side={}, price={}, qty={}, lockedQuote={}, offered={}",
                request.getSerialNum(), request.getOrderId(), request.getSymbolCode(),
                request.getOrderSide(), request.getDelegatePrice(),
                request.getDelegateCount(), request.getLockedQuoteAmount(), success);
        return success;
    }

    public boolean cancelOrder(CancelOrderRequest request) {
        request.setSerialNum(serialNumGenerator.next());
        int length = request.encode(encodeBuffer, 0);
        boolean success = clusterClient.offer(encodeBuffer, 0, length);
        log.info("cancelOrder: serialNum={}, orderId={}, symbolCode={}, offered={}",
                request.getSerialNum(), request.getOrderId(), request.getSymbolCode(), success);
        return success;
    }

    public boolean amendOrder(AmendOrderRequest request) {
        request.setSerialNum(serialNumGenerator.next());
        int length = request.encode(encodeBuffer, 0);
        boolean success = clusterClient.offer(encodeBuffer, 0, length);
        log.info("amendOrder: serialNum={}, orderId={}, symbolCode={}, newPrice={}, newQty={}, offered={}",
                request.getSerialNum(), request.getOrderId(), request.getSymbolCode(),
                request.getNewDelegatePrice(), request.getNewDelegateCount(), success);
        return success;
    }
}
