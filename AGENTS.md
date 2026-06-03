# 仓库指南

## 项目结构

这是一个面向中心化交易所的 Java 17 Maven 多模块工程：

- `common`：协议 DTO、枚举、工具类，以及 SBE schema/codecs。
- `matching`：Spring Boot 撮合引擎服务，包含 Aeron Cluster、快照、校验和结果广播。
- `counter`：Spring Boot mock counter/client 服务，用于向撮合集群发送编码后的命令。

包名位于 `com.laser.exchange` 下：

- `com.laser.exchange.common`
- `com.laser.exchange.matching`
- `com.laser.exchange.counter`

## 构建与测试

从仓库根目录执行命令：

```bash
mvn test
mvn package -DskipTests
mvn -pl common generate-sources
mvn -pl matching -am test
mvn -pl counter -am test
```

SBE schema 位于 `common/src/main/resources/sbe`；生成的 Java 源码位于 `common/target/generated-sources/sbe`。不要编辑生成源码。

## 本地约定

保持现有 Java 风格和 Aeron JVM 模块参数。SBE schema、枚举和 wire format 变更都应视为兼容性敏感变更，并在同一次修改中同步更新所有依赖模块。

生产代码和测试代码都不要使用 Java `record` 写法；需要数据载体时使用显式 Java 类，并清楚声明字段、构造方法和访问器。

回答问题或提出修改方案时，使用资深交易系统工程师视角，优先考虑适合交易所系统的生产级方案。

每次回答都必须使用中文，除非用户明确要求使用其他语言。

不要提交 `target/`、本地日志、pid 文件或 IDE 元数据。
