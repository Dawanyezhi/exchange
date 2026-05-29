package com.laser.exchange.matching.result;

import com.laser.exchange.matching.config.AeronClusterConfiguration;
import io.aeron.Aeron;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MDC broadcaster 的 Spring 壳，惰性持有。
 *
 * <p>cluster 启动完成后由 {@code MatchEngineClusteredService.onStart} 调用 {@link #init(Aeron)}
 * 完成底层 Aeron publication 创建；Spring 只负责生命周期。
 *
 * <p><b>端口规则</b>：3 节点共机部署时每节点必须使用唯一的 control 端口，
 * 避免 {@code BindException: Address already in use}。
 * 配置 {@code laser.matching.result-broadcast.base-port} 为基础值 (默认 40456)，
 * 实际端口 = base-port + nodeId。
 */
@Slf4j
@Component
public class ResultMdcBroadcasterHolder {

    @Value("${laser.matching.result-broadcast.host:localhost}")
    private String host;

    @Value("${laser.matching.result-broadcast.base-port:40456}")
    private int basePort;

    @Value("${laser.matching.result-broadcast.stream-id:1002}")
    private int streamId;

    @Value("${laser.matching.result-broadcast.enabled:true}")
    private boolean enabled;

    @Resource
    private AeronClusterConfiguration aeronClusterConfiguration;

    private volatile ResultMdcBroadcaster broadcaster;
    private volatile String effectiveChannel;

    public synchronized void init(Aeron aeron) {
        if (!enabled) {
            log.info("[ResultMdcBroadcasterHolder] disabled by config, skip init");
            return;
        }
        if (broadcaster != null) {
            return;
        }
        int nodeId = aeronClusterConfiguration.getNodeId();
        int controlPort = basePort + nodeId;
        effectiveChannel = "aeron:udp?control=" + host + ":" + controlPort + "|control-mode=dynamic";
        broadcaster = new ResultMdcBroadcaster(aeron, effectiveChannel, streamId);
        log.info("[ResultMdcBroadcasterHolder] initialized nodeId={}, channel={}, streamId={}",
                nodeId, effectiveChannel, streamId);
    }

    public ResultMdcBroadcaster get() {
        return broadcaster;
    }

    public String getChannel() { return effectiveChannel; }
    public int getStreamId() { return streamId; }

    @PreDestroy
    public void close() {
        if (broadcaster != null) {
            broadcaster.close();
        }
    }
}

