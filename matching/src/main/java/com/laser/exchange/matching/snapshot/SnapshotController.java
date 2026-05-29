package com.laser.exchange.matching.snapshot;

import com.laser.exchange.matching.cluster.ClusterRoleHolder;
import com.laser.exchange.matching.cluster.MatchEngineClusteredService;
import com.laser.exchange.matching.config.AeronClusterConfiguration;
import io.aeron.cluster.ClusterTool;
import io.aeron.cluster.service.Cluster;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 手动快照触发接口。
 *
 * <p>只有 LEADER 接收触发；非 LEADER 返回 423 LOCKED 并提示当前角色。
 */
@Slf4j
@RestController
@RequestMapping("/snapshot")
public class SnapshotController {

    @Resource
    private MatchEngineClusteredService clusteredService;

    @Resource
    private ClusterRoleHolder roleHolder;

    @Resource
    private AeronClusterConfiguration aeronClusterConfiguration;

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> trigger() {
        Map<String, Object> body = new HashMap<>();
        if (!roleHolder.isLeader()) {
            body.put("success", false);
            body.put("role", roleHolder.getCurrentRole().name());
            body.put("message", "only leader can take snapshot");
            return ResponseEntity.status(HttpStatus.LOCKED).body(body);
        }
        Cluster cluster = clusteredService.getCluster();
        if (cluster == null) {
            body.put("success", false);
            body.put("message", "cluster not ready");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            File consensusModuleDir = new File(
                    aeronClusterConfiguration.getBaseDir(),
                    AeronClusterConfiguration.getCLUSTER_DIR_PREFIX()
                            + aeronClusterConfiguration.getNodeId() + "/consensus");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean ok = ClusterTool.snapshot(consensusModuleDir, new PrintStream(out));
            body.put("success", ok);
            body.put("role", "LEADER");
            body.put("memberId", cluster.memberId());
            body.put("output", out.toString().trim());
            log.info("[SnapshotController] manual snapshot triggered, ok={}, output={}", ok, out.toString().trim());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("[SnapshotController] snapshot trigger failed", e);
            body.put("success", false);
            body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }
}
