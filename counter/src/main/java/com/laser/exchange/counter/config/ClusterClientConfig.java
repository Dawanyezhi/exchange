package com.laser.exchange.counter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "laser.cluster.client")
public class ClusterClientConfig {

    /**
     * MediaDriver base directory
     */
    private String baseDir;

    /**
     * Aeron Cluster ingress endpoints, format: "0=host:port,1=host:port,2=host:port"
     */
    private String ingressEndpoints;

    /**
     * Egress channel for receiving cluster responses
     */
    private String egressChannel;

    /**
     * Number of cluster nodes
     */
    private int nodeCount;
}
