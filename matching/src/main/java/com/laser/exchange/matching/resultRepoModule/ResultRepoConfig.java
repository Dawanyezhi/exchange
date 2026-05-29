package com.laser.exchange.matching.resultRepoModule;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * resultRepoModule 的 Spring 装配。
 *
 * <p>Sprint 1 阶段提供 {@link InMemoryResultRepository} 作为默认实现，便于单元 / 集成测试。
 * Sprint 2 整合 {@link ArchiveResultRepository} 时，通过 {@code @Profile("aeron")} 切换到
 * Aeron Archive 后端，并由 {@code ClusterNodeBoot} 在 cluster 启动后注入 Aeron / AeronArchive 实例。
 *
 * <p>切换方式：在 application.properties 设置 {@code spring.profiles.active=aeron}。
 */
@Configuration
public class ResultRepoConfig {

    /**
     * 默认 (无 profile / 测试) 使用内存实现。
     */
    @Bean
    @Profile("!aeron")
    public ResultRepository inMemoryResultRepository() {
        return new InMemoryResultRepository();
    }
}
