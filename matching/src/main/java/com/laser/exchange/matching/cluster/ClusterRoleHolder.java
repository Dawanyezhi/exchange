package com.laser.exchange.matching.cluster;

import io.aeron.cluster.service.Cluster;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 集群角色发布/查询组件。
 *
 * <p>当前用于快照调度和手动快照接口判断 Leader，不承担结果广播职责。
 */
@Slf4j
@Component
public class ClusterRoleHolder {

    @Getter
    private volatile Cluster.Role currentRole = Cluster.Role.FOLLOWER;

    /**
     * 由 {@code MatchEngineClusteredService.onStart / onRoleChange} 调用。
     */
    public void update(Cluster.Role role) {
        Cluster.Role old = this.currentRole;
        this.currentRole = role;
        if (old != role) {
            log.info("[ClusterRoleHolder] role changed {} -> {}", old, role);
        }
    }

    public boolean isLeader() {
        return currentRole == Cluster.Role.LEADER;
    }
}
