package com.laser.exchange.matching.cluster;

import io.aeron.cluster.service.Cluster;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 集群角色发布/查询的小组件，打破 ClusteredService ↔ CommandDispatcher 的双向依赖。
 *
 * <p><b>底层逻辑</b>：CommandDispatcher 只需要知道"当前是不是 LEADER"，无须整个 ClusteredService 对象；
 * 拆出本类后依赖关系变成单向：
 * <pre>
 *   ClusteredService ──写入──▶ ClusterRoleHolder ◀──读取── CommandDispatcher
 *           │
 *           └──调用──▶ CommandDispatcher
 * </pre>
 *
 * <p><b>线程模型</b>：写入端是 cluster 状态机线程 (单线程)；读取端是同一线程；用 volatile 保证可见性即可。
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
