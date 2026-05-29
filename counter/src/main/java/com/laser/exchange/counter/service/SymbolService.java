package com.laser.exchange.counter.service;

import com.laser.exchange.common.TradeSwitchRequest;
import com.laser.exchange.common.UpDownSymbolRequest;
import com.laser.exchange.common.enums.SymbolOpEnum;
import com.laser.exchange.counter.client.AeronClusterClientService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.springframework.stereotype.Service;

/**
 * 控制面命令发送：上下币 + 开关交易。
 *
 * <p>不分配 serialNum (服务端不校验)，与 OrderService (数据面) 解耦。
 */
@Slf4j
@Service
public class SymbolService {

    @Resource
    private AeronClusterClientService clusterClient;

    private final MutableDirectBuffer encodeBuffer = new ExpandableArrayBuffer(256);

    public boolean listSymbol(int symbolCode, String symbolName, long baseCoinId, long quoteCoinId) {
        UpDownSymbolRequest req = UpDownSymbolRequest.builder()
                .op(SymbolOpEnum.LIST)
                .symbolCode(symbolCode)
                .symbolName(symbolName)
                .baseCoinId(baseCoinId)
                .quoteCoinId(quoteCoinId)
                .build();
        int len = req.encode(encodeBuffer, 0);
        boolean ok = clusterClient.offer(encodeBuffer, 0, len);
        log.info("listSymbol code={}, name={}, base={}, quote={}, offered={}",
                symbolCode, symbolName, baseCoinId, quoteCoinId, ok);
        return ok;
    }

    public boolean delistSymbol(int symbolCode) {
        UpDownSymbolRequest req = UpDownSymbolRequest.builder()
                .op(SymbolOpEnum.DELIST)
                .symbolCode(symbolCode)
                .symbolName("")
                .build();
        int len = req.encode(encodeBuffer, 0);
        boolean ok = clusterClient.offer(encodeBuffer, 0, len);
        log.info("delistSymbol code={}, offered={}", symbolCode, ok);
        return ok;
    }

    public boolean enableTrade(int symbolCode) {
        return switchTrade(symbolCode, true);
    }

    public boolean disableTrade(int symbolCode) {
        return switchTrade(symbolCode, false);
    }

    private boolean switchTrade(int symbolCode, boolean on) {
        TradeSwitchRequest req = TradeSwitchRequest.builder()
                .symbolCode(symbolCode)
                .switchOn(on)
                .build();
        int len = req.encode(encodeBuffer, 0);
        boolean ok = clusterClient.offer(encodeBuffer, 0, len);
        log.info("switchTrade code={}, on={}, offered={}", symbolCode, on, ok);
        return ok;
    }
}
