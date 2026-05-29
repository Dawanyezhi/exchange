package com.laser.exchange.matching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 1. aeron cluster核心组件构造 配置
 * 2. 复制状态机实现及整合matchEngine
 * 3. 下、改、撤sbe定义 ai辅助实现
 * 4. 核心命令调度分发机制
 * 5. 事件产出机制 sbe定义 ai辅助实现
 * 6. 客户端实现，公共模块的抽象（下改撤command event公共抽取）
 * 7. 测试客户端到服务端功能
 * 8. 集群启动脚本，运维工具，客户端启动脚本，场景相关的测试工具
 * 9. 核心链路的单元测试
 */
@SpringBootApplication
public class LaserMatchingEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(LaserMatchingEngineApplication.class, args);
    }
}