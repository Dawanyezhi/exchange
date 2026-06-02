# Matching 模块指南

## 项目概览

`matching` 是 `exchange` 多模块工程中的撮合引擎服务，基于 Java 17、Spring Boot 3.4.5 和 Aeron 1.49.3。

核心职责包括：

- 维护撮合引擎状态和订单簿。
- 处理下单、撤单、改单等交易命令。
- 通过 Aeron Cluster 实现复制状态机。
- 支持快照恢复、结果事件持久化和 MDC 广播。

项目不是独立仓库，构建时依赖同一 Maven reactor 中的 `common` 模块。

## 技术栈

- Java 17
- Maven
- Spring Boot 3.4.5
- Aeron 1.49.3
- Agrona
- gRPC / Protobuf
- LMAX Disruptor
- JUnit 5 / Mockito
- JMH

## 构建与运行

优先使用本模块下的 `cluster.sh` 脚本进行构建和集群管理。脚本会回到仓库根目录执行 Maven reactor 构建。

常用命令：

```bash
./cluster.sh build
./cluster.sh start-single
./cluster.sh start-all
./cluster.sh status
./cluster.sh stop-all
```

如果直接使用 Maven，在仓库根目录执行：

```bash
mvn -pl matching -am package -DskipTests
```

测试命令：

```bash
mvn -pl matching -am test
```

运行 Aeron/Agrona 相关测试或服务时，需要保留项目已有的 JVM `--add-opens` 配置，不要随意删除 `pom.xml`、`cluster.sh` 中的模块开放参数。

## 关键目录

- `src/main/java/com/laser/exchange/matching/LaserMatchingEngineApplication.java`：Spring Boot 启动入口。
- `src/main/java/com/laser/exchange/matching/core/engine`：撮合引擎入口与核心编排。
- `src/main/java/com/laser/exchange/matching/core/model`：订单、订单簿、深度、配置等核心模型。
- `src/main/java/com/laser/exchange/matching/core/service`：撮合辅助服务，如 FOK、STP、币对管理。
- `src/main/java/com/laser/exchange/matching/cluster`：Aeron Cluster 状态机、命令分发和节点角色管理。
- `src/main/java/com/laser/exchange/matching/snapshot`：快照读写、恢复与调度。
- `src/main/java/com/laser/exchange/matching/result`：撮合结果事件处理与广播。
- `src/main/java/com/laser/exchange/matching/resultRepoModule`：撮合结果仓储实现。
- `src/main/java/com/laser/exchange/matching/validation`：请求序号校验。
- `src/main/resources/application.properties`：默认服务、集群、快照、广播配置。
- `docs`：压测、架构、集群脚本和业务手册。

## 修改原则

- 每次回答都必须使用中文，除非用户明确要求使用其他语言。
- 优先遵循现有代码风格、包结构和命名方式。
- 修改撮合逻辑时，应同时检查对应单元测试和结果事件顺序约束。
- 修改 Aeron Cluster、快照、序号校验或结果仓储时，应重点关注状态机确定性和重放一致性。
- 不要引入与现有架构无关的大型抽象或框架。
- 不要随意更改 `common` 的协议、枚举或 SBE/Protobuf 相关接口；如必须修改，需要同步更新依赖方和测试。
- 不要删除已有的 JVM `--add-opens` / `--add-exports` 参数。
- Git 仓库位于 `exchange` 根目录；开始编辑前查看根目录 `git status --short`。

## 测试建议

窄范围业务修改优先运行相关测试类，例如：

```bash
mvn test -Dtest=MatchEngineTest
mvn test -Dtest=OrderBookTest
mvn test -Dtest=SnapshotManagerTest
```

涉及撮合主链路时，建议至少覆盖：

- 下单、撤单、改单。
- GTC、IOC、FOK、POST_ONLY。
- 买卖方向交叉撮合。
- 部分成交和完全成交。
- STP 策略。
- 事件生成顺序。

涉及集群或快照时，建议覆盖：

- 冷启动无快照。
- 从快照恢复。
- 请求序号恢复。
- 结果序号恢复。
- Leader/Follower 角色变更相关行为。

## 代码质量约定

- 保持撮合热路径简洁，避免不必要对象分配、JSON 序列化和日志拼接。
- 使用 `long` 表示价格和数量的 V1 逻辑中，不要重新引入 `BigDecimal`。
- 对公共接口、协议字段和持久化格式的修改需要兼容性说明。
- 新增复杂逻辑时补充有针对性的单元测试。
- 日志应服务于排障，不要在高频撮合路径加入过多 `info` 日志。
