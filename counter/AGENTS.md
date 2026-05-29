# Counter Module Guidelines

## 项目概览

- 项目名：`exchange-counter`
- 技术栈：Java 17、Maven、Spring Boot 3.4.x、Aeron Cluster Client、Lombok
- 作用：提供 mock counter HTTP API，将交易/控制指令编码为 `common` 模块中定义的 SBE 消息，并通过 Aeron Cluster 发送到撮合引擎集群。
- 默认 HTTP 端口：`10880`
- 主要内部依赖：同一 Maven reactor 中的 `common` 模块。

## 目录结构

- `src/main/java/com/laser/exchange/counter/LaserMockCounterApplication.java`：Spring Boot 入口。
- `src/main/java/com/laser/exchange/counter/controller/`：REST API 控制层。
- `src/main/java/com/laser/exchange/counter/service/`：业务服务，负责构造请求、分配序号、编码并发送。
- `src/main/java/com/laser/exchange/counter/client/`：Aeron Cluster 客户端和结果广播订阅。
- `src/main/java/com/laser/exchange/counter/config/`：Spring 配置属性绑定。
- `src/main/resources/application.properties`：端口、Aeron 集群、批量下单和结果广播配置。
- `src/main/resources/logback-spring.xml`：日志配置。
- `client.sh`：构建、启动、停止、调用接口的管理脚本。
- `docs/client-guide.html`：客户端使用文档。

## 构建与运行

常用命令：

```bash
./client.sh build
./client.sh start
./client.sh stop
./client.sh restart
./client.sh status
./client.sh log
```

手动构建：

```bash
mvn -pl counter -am package -DskipTests
```

手动运行时需要保留 Aeron 所需 JVM 参数，参考 `pom.xml` 和 `client.sh` 中的 `--add-opens` 配置。

## 主要接口

订单数据面：

- `POST /api/order/place`
- `POST /api/order/cancel`
- `POST /api/order/amend`
- `POST /api/order/batch/start`
- `POST /api/order/batch/stop`
- `GET /api/order/batch/status`

控制面：

- `POST /api/symbol/list`
- `POST /api/symbol/delist`
- `POST /api/symbol/enable-trade`
- `POST /api/symbol/disable-trade`

健康检查：

- `GET /health`

## 代码约定

- 数据面订单请求由 `OrderService` 分配 `serialNum`，通过 `RequestSerialNumGenerator` 保证递增。
- 控制面上下币/开关交易请求不分配 `serialNum`，保持与 `SymbolService` 现有注释一致。
- 请求类、枚举、SBE codec 来自 `common`，不要在本模块重复定义协议模型。
- Aeron 发送统一经过 `AeronClusterClientService.offer(...)`。
- 结果广播订阅逻辑集中在 `ResultMdcSubscriber`，默认订阅所有节点端口，Leader 在哪个节点就从哪个订阅收到结果。
- 优先沿用现有 Spring 注入风格、日志风格和包结构，避免无关重构。

## 配置注意事项

- `laser.cluster.client.base-dir` 当前是本机绝对路径，改动前确认运行环境。
- `laser.cluster.client.ingress-endpoints` 默认指向本地三节点：`localhost:9000`、`localhost:9001`、`localhost:9002`。
- `laser.matching.result-broadcast.subscribe-enabled` 可用于关闭结果广播订阅。
- 批量下单默认参数在 `application.properties` 和 `OrderController` 中均有体现，修改时保持一致性。

## 测试与验证

本仓库当前没有明显的测试源码目录。修改代码后至少执行：

```bash
mvn test
```

如果涉及协议类或 `common` 变更，先在仓库根目录执行：

```bash
mvn -pl common test
```

如果涉及运行行为，可用以下方式做基本验证：

```bash
./client.sh build
./client.sh start
curl http://localhost:10880/health
./client.sh status
./client.sh stop
```

注意：启动应用需要可连接的 Aeron Cluster；如果撮合引擎未启动，应用初始化连接可能失败。

## 工作区保护

- 不要覆盖用户已有改动。开始编辑前查看 `git status --short`。
- 当前已观察到的本地状态可能包含：
  - `src/main/resources/logback-spring.xml` 已修改
  - `.idea/` 未跟踪
- 除非用户明确要求，不要改动 IDE 配置、日志配置或清理未跟踪文件。
- 不要使用 `git reset --hard`、`git checkout --` 等破坏性命令，除非用户明确要求。

## 文档维护

- 修改接口、默认参数、运行脚本或配置项时，同步检查 `docs/client-guide.html` 和 `client.sh` 是否需要更新。
- 新增 API 时，优先补充 `client.sh` 中的调用命令，方便本地联调。
