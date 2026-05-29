package com.laser.exchange.matching.cluster;

import com.laser.exchange.matching.config.AeronClusterConfiguration;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.client.ClusterEvent;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 集群启动器
 */
@Slf4j
@Component
public class ClusterNodeBoot {

    @Resource
    AeronClusterConfiguration aeronClusterConfiguration;

    @Resource
    MatchEngineClusteredService matchEngineClusteredService;

    private volatile ClusteredMediaDriver clusteredMediaDriver;
    private volatile ClusteredServiceContainer clusteredServiceContainer;

    @PostConstruct
    void initCluster() {
        Thread clusterThread = new Thread(this::launchCluster, "aeron-cluster-main");
        clusterThread.setDaemon(true);
        clusterThread.start();
    }

    @PreDestroy
    void shutdown() {
        log.info("Shutting down Aeron cluster...");
        CloseHelper.quietClose(clusteredServiceContainer);
        CloseHelper.quietClose(clusteredMediaDriver);
        log.info("Aeron cluster shutdown complete.");
    }

    private void launchCluster() {
        try {
            // 获取nodeid
            Integer nodeId = aeronClusterConfiguration.getNodeId();

            // 获取目录 baseDir/clusterDir/aeronDir
            File baseDir = new File(aeronClusterConfiguration.getBaseDir());
            File clusterDir = new File(baseDir, AeronClusterConfiguration.getCLUSTER_DIR_PREFIX() + nodeId);
            File aeronDir = new File(clusterDir, "aeron");

            String clusterDirAbsolutePath = clusterDir.getAbsolutePath();
            String aeronDirAbsolutePath = aeronDir.getAbsolutePath();

            // 确保目录存在
            if (!clusterDir.exists() && !clusterDir.mkdirs()) {
                log.error("create clusterDir failed. {}", clusterDirAbsolutePath);
                System.exit(1);
            }

            log.info("initCluster NodeID:{}, clusterDir:{}, aeronDir:{}", nodeId, clusterDirAbsolutePath, aeronDirAbsolutePath);

            // mediaDriver
            MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                    .aeronDirectoryName(aeronDirAbsolutePath)
                    .threadingMode(ThreadingMode.DEDICATED)
                    .termBufferSparseFile(false)
                    .errorHandler(buildErrorHandler("MediaDriver"))
                    .dirDeleteOnStart(false)
                    .dirDeleteOnShutdown(false);

            // archive
            File archiveDir = new File(clusterDir, "archive");
            Archive.Context archiveContext = new Archive.Context()
                    .aeronDirectoryName(aeronDirAbsolutePath)
                    .archiveDir(archiveDir)
                    .controlChannel(aeronClusterConfiguration.getChannelPrefix() + (AeronClusterConfiguration.getARCHIVE_CONTROL_PORT_BASE() + nodeId))
                    .replicationChannel(aeronClusterConfiguration.getChannelPrefix() + (AeronClusterConfiguration.getARCHIVE_REPLICATION_PORT_BASE() + nodeId))
                    .recordingEventsChannel(aeronClusterConfiguration.getChannelPrefix() + (AeronClusterConfiguration.getARCHIVE_RECORDING_EVENTS_PORT_BASE() + nodeId))
                    .threadingMode(ArchiveThreadingMode.DEDICATED)
                    .deleteArchiveOnStart(false)
                    .errorHandler(buildErrorHandler("Archive"));

            // 共识模块
            final String clusterMembers = buildClusterMembers();
            File consensusModuleDir = new File(clusterDir, "consensus");
            ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
                    .clusterMemberId(nodeId)
                    .clusterMembers(clusterMembers)
                    .clusterDir(consensusModuleDir)
                    .ingressChannel(aeronClusterConfiguration.getChannelPrefix() + (AeronClusterConfiguration.getCLIENT_FACING_PORT_BASE() + nodeId))
                    .replicationChannel(aeronClusterConfiguration.getChannelPrefix() + (AeronClusterConfiguration.getCONSENSUS_REPLICATION_PORT_BASE() + nodeId))
                    .aeronDirectoryName(aeronDirAbsolutePath)
                    .errorHandler(buildErrorHandler("ConsensusModule"))
                    .deleteDirOnStart(false);

            // ClusteredServiceContainer 业务状态机容器
            File clusteredServiceModuleDir = new File(clusterDir, "service");
            ClusteredServiceContainer.Context clusteredServiceContainerContext = new ClusteredServiceContainer.Context()
                    .clusteredService(matchEngineClusteredService)     // 业务状态机实现
                    .clusterDir(clusteredServiceModuleDir)
                    .aeronDirectoryName(aeronDirAbsolutePath)
                    .errorHandler(buildErrorHandler("ClusteredServiceContainer"));

            log.info("initCluster begin launch cluster component. clusterMembers:{}", clusterMembers);

            clusteredMediaDriver = ClusteredMediaDriver.launch(mediaDriverContext, archiveContext, consensusModuleContext);
            clusteredServiceContainer = ClusteredServiceContainer.launch(clusteredServiceContainerContext);

            log.info("mediaDriver started. archive started. consensusModule started. clusteredServiceContainer started.");

        } catch (Exception e) {
            log.error("initCluster launch exception.", e);
            CloseHelper.quietClose(clusteredServiceContainer);
            CloseHelper.quietClose(clusteredMediaDriver);
            System.exit(1);
        }
    }

    /**
     * 构建智能 ErrorHandler
     * <p>
     * Aeron Cluster 将集群事件（如 leader heartbeat timeout）也通过 errorHandler 回调传递，
     * 这些 ClusterEvent 是 Raft 协议正常运行的一部分，不应以 ERROR 级别记录。
     * <p>
     * 路由规则：
     * - ClusterEvent → WARN（集群事件，如心跳超时、选举等，属于正常行为）
     * - 其他 Throwable → ERROR（真正的组件异常）
     */
    private ErrorHandler buildErrorHandler(String component) {
        return throwable -> {
            if (throwable instanceof ClusterEvent) {
                log.warn("ClusterEvent [{}] {}", component, throwable.getMessage());
            } else {
                log.error("[{}] errorHandler.", component, throwable);
            }
        };
    }

    /**
     * 构建集群成员配置字符串
     * <p>
     * 格式:
     * memberId,clientFacingEndpoint,memberFacingEndpoint,logEndpoint,transferEndpoint,archiveEndpoint|
     * memberId,clientFacingEndpoint,memberFacingEndpoint,logEndpoint,transferEndpoint,archiveEndpoint|
     * memberId,clientFacingEndpoint,memberFacingEndpoint,logEndpoint,transferEndpoint,archiveEndpoint|...
     * <p>
     * 说明:
     * - clientFacingEndpoint: 客户端连接端点
     * - memberFacingEndpoint: 集群成员间通信端点
     * - logEndpoint: 日志复制端点
     * - transferEndpoint: 数据传输端点
     * - archiveEndpoint: Archive 控制端点
     * <p>
     * 0,ingress:port,consensus:port,log:port,catchup:port,archive:port| \
     * 1,ingress:port,consensus:port,log:port,catchup:port,archive:port| ...
     */
    protected String buildClusterMembers() {
        final StringBuilder memberUri = new StringBuilder();

        for (int i = 0; i < aeronClusterConfiguration.getClusterNodeSize(); i++) {
            if (i > 0) {
                memberUri.append('|');
            }

            // 节点 ID
            memberUri.append(i).append(',');

            // Client-facing endpoint (客户端连接)
            memberUri.append(aeronClusterConfiguration.getNodeIp()).append(":").append(AeronClusterConfiguration.getCLIENT_FACING_PORT_BASE() + i).append(',');

            // Member-facing endpoint (集群内部通信)
            memberUri.append(aeronClusterConfiguration.getNodeIp()).append(":").append(AeronClusterConfiguration.getMEMBER_FACING_PORT_BASE() + i).append(',');

            // Log endpoint (日志复制)
            memberUri.append(aeronClusterConfiguration.getNodeIp()).append(":").append(AeronClusterConfiguration.getLOG_PORT_BASE() + i).append(',');

            // Transfer endpoint (快照传输)
            memberUri.append(aeronClusterConfiguration.getNodeIp()).append(":").append(AeronClusterConfiguration.getTRANSFER_PORT_BASE() + i).append(',');

            // Archive endpoint (Archive 控制)
            memberUri.append(aeronClusterConfiguration.getNodeIp()).append(":").append(AeronClusterConfiguration.getARCHIVE_CONTROL_PORT_BASE() + i);
        }

        return memberUri.toString();
    }
}
