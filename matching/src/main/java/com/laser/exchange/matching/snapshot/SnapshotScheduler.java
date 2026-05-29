package com.laser.exchange.matching.snapshot;

import com.laser.exchange.matching.cluster.ClusterRoleHolder;
import com.laser.exchange.matching.cluster.MatchEngineClusteredService;
import com.laser.exchange.matching.config.AeronClusterConfiguration;
import io.aeron.cluster.ClusterTool;
import io.aeron.cluster.service.Cluster;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;

/**
 * 定时快照触发器。
 *
 * <p><b>闭环策略</b>：
 * <ul>
 *   <li>默认每 5 分钟一次，可通过 {@code laser.matching.snapshot.interval-ms} 覆盖（测试用 5s）</li>
 *   <li>只在 LEADER 节点触发；follower 节点定时跳过</li>
 *   <li>触发方式：ClusterTool.snapshot() → aeron cluster 内部会回调 ClusteredService.onTakeSnapshot</li>
 * </ul>
 */
@Slf4j
@Component
@EnableScheduling
public class SnapshotScheduler {

    @Resource
    private MatchEngineClusteredService clusteredService;

    @Resource
    private ClusterRoleHolder roleHolder;

    @Resource
    private AeronClusterConfiguration aeronClusterConfiguration;

    @Value("${laser.matching.snapshot.scheduler-enabled:true}")
    private boolean enabled;


    // todo 不建议在撮合状态机中使用spring调度，将定时任务抽取到外部定时任务平台，通过 @SnapshotController 的方式进行调度
    @Scheduled(fixedRateString = "${laser.matching.snapshot.interval-ms:300000}",
               initialDelayString = "${laser.matching.snapshot.initial-delay-ms:60000}")
    public void triggerPeriodicSnapshot() {
        if (!enabled) {
            return;
        }
        if (!roleHolder.isLeader()) {
            log.debug("[SnapshotScheduler] skip: not leader (role={})", roleHolder.getCurrentRole());
            return;
        }
        Cluster cluster = clusteredService.getCluster();
        if (cluster == null) {
            log.warn("[SnapshotScheduler] cluster not ready yet");
            return;
        }
        try {
            File consensusModuleDir = new File(
                    aeronClusterConfiguration.getBaseDir(),
                    AeronClusterConfiguration.getCLUSTER_DIR_PREFIX() + aeronClusterConfiguration.getNodeId() + "/consensus");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            log.info("[SnapshotScheduler] triggering snapshot via ClusterTool, dir={}", consensusModuleDir);

            // 内部会回调 ClusteredService.onTakeSnapshot
            boolean ok = ClusterTool.snapshot(consensusModuleDir, new PrintStream(out));
            log.info("[SnapshotScheduler] snapshot triggered, ok={}, output={}", ok, out.toString().trim());
        } catch (Exception e) {
            log.error("[SnapshotScheduler] snapshot trigger failed", e);
        }
    }
}
