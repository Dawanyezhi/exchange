package com.laser.exchange.matching.cluster;

import com.laser.exchange.matching.config.AeronClusterConfiguration;
import com.laser.exchange.matching.core.engine.MatchEngine;
import com.laser.exchange.matching.result.MatchResultEventsHelper;
import com.laser.exchange.matching.result.ResultMdcBroadcasterHolder;
import com.laser.exchange.matching.resultRepoModule.ResultRepository;
import com.laser.exchange.matching.snapshot.AeronImageSnapshotReader;
import com.laser.exchange.matching.snapshot.AeronPublicationSnapshotWriter;
import com.laser.exchange.matching.snapshot.SnapshotManager;
import com.laser.exchange.matching.validation.SerialNumValidator;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.agrona.DirectBuffer;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * aeron cluster对接撮合引擎
 * - 实现状态机维度的下、改、撤核心逻辑
 * - 抽象出对应的请求参数
 * - 转换成sbe
 * - 实现sbe对应的编解码器
 * - 根据命令的编号给出commandDispatcher逻辑
 */
@Slf4j
@Service
public class MatchEngineClusteredService implements ClusteredService {

    @Resource
    private AeronClusterConfiguration aeronClusterConfiguration;

    @Resource
    private CommandDispatcher commandDispatcher;

    @Resource
    private MatchEngine matchEngine;

    @Resource
    private SerialNumValidator serialNumValidator;

    @Resource
    private MatchResultEventsHelper eventsHelper;

    @Resource
    private ResultRepository resultRepository;

    @Resource
    private ResultMdcBroadcasterHolder broadcasterHolder;

    @Resource
    private ClusterRoleHolder roleHolder;

    private final SnapshotManager snapshotManager = new SnapshotManager();

    private volatile Cluster cluster;

    @Getter
    private volatile Cluster.Role currentRole = Cluster.Role.FOLLOWER;

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        this.currentRole = cluster.role();
        roleHolder.update(currentRole);
        log.info("onStart nodeId:{}, role:{}", aeronClusterConfiguration.getNodeId(), currentRole);
        writeRoleFile(currentRole);

        // 初始化 MDC 广播器（复用 cluster 内置 Aeron client）
        broadcasterHolder.init(cluster.aeron());

        // 如果 raftlog 包含快照，先恢复
        if (snapshotImage != null) {
            log.info("[onStart] loading snapshot from image sessionId={}", snapshotImage.sessionId());
            try {
                AeronImageSnapshotReader reader = new AeronImageSnapshotReader(snapshotImage);
                SnapshotManager.LoadResult result = snapshotManager.loadSnapshot(
                        matchEngine.getMatchEngineState(),
                        serialNumValidator,
                        eventsHelper,
                        resultRepository,
                        reader
                );
                log.info("[onStart] snapshot loaded: maxReq={}, maxRes={}, entries={}",
                        result.maxProcessedRequestSerialNum, result.maxResultSerialNum, result.entryCount);
            } catch (Exception e) {
                log.error("[onStart] snapshot load failed", e);
                throw e;
            }
        } else {
            log.info("[onStart] no snapshot image (cold start)");
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        log.info("onSessionOpen sessionId:{}", session.id());
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason closeReason) {
        log.info("onSessionClose sessionId:{}, reason:{}", session.id(), closeReason);
    }

    @Override
    public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer, int offset, int length, Header header) {
        commandDispatcher.dispatchCommand(timestamp, buffer, offset, length);
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        log.info("onTimerEvent correlationId:{}", correlationId);
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        long takenAt = cluster != null ? cluster.time() : System.currentTimeMillis();
        long maxReq = serialNumValidator.getLastSerialNum();
        long maxRes = eventsHelper.getNextResultSerialNum() - 1;
        log.info("onTakeSnapshot begin, takenAt={}, maxReq={}, maxRes={}", takenAt, maxReq, maxRes);
        try {
            AeronPublicationSnapshotWriter writer = new AeronPublicationSnapshotWriter(snapshotPublication);
            int entryCount = snapshotManager.takeSnapshot(
                    matchEngine.getMatchEngineState(),
                    maxReq,
                    maxRes,
                    takenAt,
                    writer
            );
            log.info("onTakeSnapshot done, entries={}", entryCount);
        } catch (Exception e) {
            log.error("onTakeSnapshot failed", e);
            throw e;
        }
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        // 上一term的角色
        Cluster.Role previousRole = this.currentRole;
        // 当前角色
        this.currentRole = newRole;
        roleHolder.update(newRole);
        log.info("onRoleChange memberId:{}, {} -> {}", cluster.memberId(), previousRole, newRole);
        writeRoleFile(newRole);
    }

    @Override
    public void onTerminate(Cluster cluster) {
        log.info("onTerminate memberId:{}", cluster.memberId());
        deleteRoleFile();
    }

    /**
     * 当前节点是否为 Leader
     */
    public boolean isLeader() {
        return currentRole == Cluster.Role.LEADER;
    }

    /**
     * 获取 Cluster 实例（onStart 后可用）
     */
    public Cluster getCluster() {
        return cluster;
    }

    /**
     * 将当前角色写入文件，供 cluster.sh status 读取
     * 路径: {baseDir}/pids/node-{nodeId}.role
     */
    private void writeRoleFile(Cluster.Role role) {
        try {
            File roleFile = getRoleFile();
            roleFile.getParentFile().mkdirs();
            Files.writeString(roleFile.toPath(), role.name(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("writeRoleFile failed.", e);
        }
    }

    private void deleteRoleFile() {
        try {
            File roleFile = getRoleFile();
            Files.deleteIfExists(roleFile.toPath());
        } catch (IOException e) {
            log.warn("deleteRoleFile failed.", e);
        }
    }

    private File getRoleFile() {
        String baseDir = aeronClusterConfiguration.getBaseDir();
        Integer nodeId = aeronClusterConfiguration.getNodeId();
        return new File(baseDir, "pids/node-" + nodeId + ".role");
    }
}
