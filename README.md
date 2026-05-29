# Exchange

中心化交易所 Spring Boot 多模块工程。

## Modules

- `common`: 公共协议、请求/结果 DTO、枚举、工具类和 SBE schema。
- `matching`: 撮合引擎服务，基于 Aeron Cluster 处理交易命令、快照和结果广播。
- `counter`: mock counter 服务，通过 HTTP API 编码请求并发送到撮合引擎集群。

## Versions

- Java: 17
- Spring Boot: 3.4.5
- Aeron: 1.49.3
- Agrona: 2.3.0
- SBE Tool: 1.36.2

暂未引入 Spring Cloud，当前先保留 Spring Boot 多模块微服务骨架。

## Build

```bash
mvn test
mvn package -DskipTests
```

单独构建模块及其依赖：

```bash
mvn -pl common generate-sources
mvn -pl matching -am package -DskipTests
mvn -pl counter -am package -DskipTests
```

## SBE

SBE schema 位于 `common/src/main/resources/sbe`。Maven `generate-sources` 阶段会生成源码到：

```text
common/target/generated-sources/sbe
```

生成包路径：

- `com.laser.exchange.common.codec`
- `com.laser.exchange.common.codec.snapshot`
