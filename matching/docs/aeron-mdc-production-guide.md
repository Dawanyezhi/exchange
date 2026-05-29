# Aeron MDC 广播生产使用方式和注意事项

本文说明 `laser-matching-engine-v2` 中 MatchResult Aeron MDC 广播在生产环境的推荐用法、部署配置、下游接入方式和运维注意事项。

## 1. 适用范围

本项目当前的 MDC 广播用于把撮合结果事件实时推送给下游系统，例如：

- 清算、资金、仓位服务。
- 风控、监控、审计服务。
- 行情或 UI 推送网关。
- 测试环境中的 mock-counter / ResultMdcSubscriber。

MDC 只承担实时广播职责，不承担结果持久化和断线补发职责。结果持久化由 `ResultRepository` / `ArchiveResultRepository` 负责。

## 2. 当前实现语义

核心代码位置：

- `src/main/java/com/laser/exchange/matching/result/ResultMdcBroadcaster.java`
- `src/main/java/com/laser/exchange/matching/result/ResultMdcBroadcasterHolder.java`
- `src/main/java/com/laser/exchange/matching/cluster/CommandDispatcher.java`
- `src/main/resources/application.properties`

当前实现有几个关键语义：

1. 每个节点启动时都会创建一个 MDC `ExclusivePublication`。
2. 每个节点的 MDC control 端口为 `base-port + nodeId`。
3. 所有节点都会持久化 MatchResult。
4. 只有 LEADER 节点会执行 MDC 广播，FOLLOWER 不广播，避免三副本重复推送。
5. MDC 广播是 live broadcast，撮合完成后直接推送。
6. `Publication.offer()` 非阻塞；如果发生背压或 publication 不可用，当前实现会丢弃本次 MDC 推送，不阻塞撮合主链路。

因此，生产上应把 MDC 理解为“低延迟实时通知通道”，不能把它作为唯一结果账本。

## 3. 服务端配置

默认配置：

```properties
laser.matching.result-broadcast.enabled=true
laser.matching.result-broadcast.host=localhost
laser.matching.result-broadcast.base-port=40456
laser.matching.result-broadcast.stream-id=1002
```

生产环境建议配置：

```properties
laser.matching.result-broadcast.enabled=true
laser.matching.result-broadcast.host=<本机对下游可达的内网 IP 或主机名>
laser.matching.result-broadcast.base-port=40456
laser.matching.result-broadcast.stream-id=1002
```

注意事项：

- `host` 不要使用 `localhost`，除非下游 subscriber 与撮合节点在同一台机器上。
- `base-port + nodeId` 必须在每个节点上可绑定，且对下游机器网络可达。
- `stream-id` 需要与下游订阅端保持一致。
- 多套环境共用网络时，应为不同环境分配不同端口段，避免测试环境误接生产流量。

三节点示例：

| 节点 | nodeId | MDC control 端口 |
| --- | ---: | ---: |
| matching-0 | 0 | 40456 |
| matching-1 | 1 | 40457 |
| matching-2 | 2 | 40458 |

## 4. 下游订阅方式

发布端 channel 由服务端自动生成，格式如下：

```text
aeron:udp?control=<publisher-host>:<base-port + nodeId>|control-mode=dynamic
```

订阅端 channel 格式如下：

```text
aeron:udp?endpoint=<subscriber-host>:<subscriber-port>|control=<publisher-host>:<publisher-control-port>
```

单节点订阅示例：

```text
aeron:udp?endpoint=10.0.2.20:40500|control=10.0.1.10:40456
streamId=1002
```

生产三节点集群推荐让下游同时订阅所有撮合节点的 MDC control 端口：

```text
aeron:udp?endpoint=10.0.2.20:40500|control=10.0.1.10:40456
aeron:udp?endpoint=10.0.2.20:40501|control=10.0.1.11:40457
aeron:udp?endpoint=10.0.2.20:40502|control=10.0.1.12:40458
```

原因：

- 当前只有 LEADER 广播；FOLLOWER 即使被订阅也不会发业务结果。
- 发生 Leader 切换后，新 LEADER 已经有本节点 publication，可以继续从自己的 control 端口广播。
- 下游如果只订阅旧 LEADER，切主后会收不到新 LEADER 的广播。

下游处理要求：

- 使用 `resultSerialNum` 做连续性校验。
- 按 SBE / Protobuf 约定解码 `MatchResult`，不要用日志文本作为业务协议。
- 如果订阅多个节点，仍应按 `resultSerialNum` 做去重和顺序校验，防止角色切换边界或异常场景出现重复处理。
- 业务处理必须幂等，至少以 `resultSerialNum` 或业务唯一键做幂等保护。

## 5. 交付语义和补偿策略

当前 MDC 广播不是可靠队列，生产上必须接受以下语义：

- 不保证下游一定收到每条消息。
- 不保证下游断线后自动补发历史消息。
- 不保证背压时等待下游消费。
- 不建议用 MDC 承载必须强一致落账的唯一链路。

下游应采用如下补偿策略：

1. 实时路径从 MDC 消费 MatchResult。
2. 用 `resultSerialNum` 检测缺口。
3. 发现缺口、重启、切主或长时间断线后，从结果持久化仓储或未来的 archive replay 服务补齐。
4. 补齐后再恢复实时消费，避免跳号处理。

当前代码中 MDC 与持久化的关系是：

```text
MatchResult batch
  -> 每个节点 resultRepository.persist(batch)
  -> 仅 LEADER ResultMdcBroadcaster.broadcastBatch(batch)
```

因此，持久化结果才是补偿和审计的依据。

## 6. 网络和端口规划

生产部署前需要确认：

- 撮合节点的 `laser.matching.result-broadcast.host` 是下游可访问地址。
- 下游机器到所有撮合节点的 MDC control 端口可达。
- 防火墙、安全组、Kubernetes NetworkPolicy 已放通 UDP。
- 下游 subscriber 的 `endpoint` 端口在本机唯一，不能多个订阅实例绑定同一个 UDP 端口。
- 多网卡机器上，publisher 和 subscriber 使用的 IP 要在同一可路由网络内。
- 不要跨公网传输撮合结果；如必须跨机房，应通过专线、VPN 或独立中继服务。

端口规划建议：

| 用途 | 示例端口段 |
| --- | --- |
| 撮合 MDC control | 40456-40499 |
| 下游 subscriber endpoint | 40500-40599 |
| 测试 / 压测环境 | 使用独立端口段 |

## 7. 背压和性能注意事项

当前 `ResultMdcBroadcaster` 在 `offer()` 返回负数时会计入 `droppedCount` 并按频率打印 warn 日志。生产上需要关注这些日志：

```text
[ResultMdcBroadcaster] offer back-pressured, dropped ... serialNum=..., code=...
```

常见原因：

- 下游消费太慢。
- 下游处理逻辑在 Aeron polling 线程中做了阻塞操作。
- 网络丢包或网络队列拥塞。
- subscriber 没有正确连接到 publisher control 端口。
- MediaDriver 或 JVM 所在机器 CPU 资源不足。

建议：

- 下游 poll 线程只负责解码和入队，不要直接做数据库、HTTP、RPC 等慢操作。
- 下游业务处理使用单独线程池或队列，但必须保持结果顺序或按分区顺序处理。
- 对 `resultSerialNum` 缺口建立告警。
- 对 MDC dropped 日志建立告警。
- 高峰压测时同时观察撮合 TPS、MDC dropped、下游消费延迟和机器 UDP 丢包。

## 8. Leader 切换注意事项

Leader 切换时需要特别关注：

- 新 LEADER 只会从自己的 MDC control 端口广播。
- 下游必须订阅所有节点，或能根据集群角色变化快速切换订阅目标。
- 切换窗口内可能出现短暂中断，下游必须用 `resultSerialNum` 检测缺口。
- 不要根据 MDC 是否有消息来判断撮合集群是否健康；FOLLOWER 正常情况下也不会广播。

建议的下游策略：

1. 同时连接所有节点的 MDC control 端口。
2. 接收端维护最后处理成功的 `resultSerialNum`。
3. 发现 `current != last + 1` 时暂停业务推进，触发补偿流程。
4. 补偿完成后继续从 MDC 实时流消费。

## 9. 可观测性建议

当前代码已有：

- `broadcastCount`
- `droppedCount`
- 初始化日志：`publisher initialized channel=..., streamId=...`
- 关闭日志：`closed. broadcast=..., dropped=...`
- 背压丢弃 warn 日志

生产环境建议进一步补充或外接：

- 将 `broadcastCount`、`droppedCount` 暴露为 Micrometer / Prometheus 指标。
- 记录当前节点角色：LEADER / FOLLOWER。
- 下游记录 `lastResultSerialNum`、gap 次数、重复次数、解码失败次数。
- 对 dropped、gap、解码失败、长时间无消息建立告警。

## 10. 安全和权限

MDC 使用 UDP 广播/多目的地控制，不自带认证、鉴权和加密。生产上应依赖网络边界控制：

- 只允许授权下游网段访问 MDC 端口。
- 不要把 MDC 端口暴露到公网。
- 不要在 MDC 中加入不必要的敏感字段。
- 跨安全域传输时使用网关、中继或 VPN，而不是直接开放 UDP 端口。

## 11. 上线检查清单

上线前逐项确认：

- [ ] 三个撮合节点的 `nodeId` 唯一。
- [ ] `base-port + nodeId` 端口没有冲突。
- [ ] `laser.matching.result-broadcast.host` 是下游可达地址。
- [ ] 下游已订阅所有节点的 MDC control 端口。
- [ ] 下游每个 subscription 使用独立 `endpoint` 端口。
- [ ] 下游按 `resultSerialNum` 做连续性校验。
- [ ] 下游处理逻辑具备幂等能力。
- [ ] 已定义 MDC 缺口补偿流程。
- [ ] 已压测峰值流量下的 dropped、gap、延迟。
- [ ] 已配置 dropped/gap/解码失败告警。
- [ ] 已验证 Leader 切换后下游仍可收到新结果。

## 12. 常见故障排查

### 12.1 下游收不到消息

检查顺序：

1. 服务端日志是否出现 `ResultMdcBroadcaster publisher initialized`。
2. 当前是否有 LEADER 节点；FOLLOWER 不广播是正常行为。
3. 下游订阅的 `control` 是否指向当前 LEADER 或所有节点。
4. `streamId` 是否一致。
5. publisher `host` 是否为下游可达地址，而不是 `localhost`。
6. UDP 端口是否被防火墙、安全组或容器网络拦截。
7. subscriber `endpoint` 端口是否被其他进程占用。

### 12.2 下游收到跳号

处理方式：

1. 立即记录缺失的 `resultSerialNum` 范围。
2. 暂停依赖连续结果的业务推进。
3. 从持久化结果或 archive replay 补齐缺失数据。
4. 校验补齐后再恢复实时消费。

跳号常见原因：

- MDC 背压导致服务端丢弃实时广播。
- 下游重启或网络抖动。
- Leader 切换窗口。
- 下游解码失败后丢弃消息。

### 12.3 服务端出现 dropped 日志

处理方式：

1. 检查下游消费是否阻塞。
2. 检查网络 UDP 丢包和机器 CPU。
3. 检查是否有异常 subscriber 占用端口但不消费。
4. 对高峰流量做压测，必要时拆分下游消费、优化处理线程模型。

### 12.4 端口绑定失败

典型原因：

- 同机多节点没有按 `base-port + nodeId` 使用不同端口。
- 上一次进程未退出干净。
- 测试环境和生产环境复用了同一端口段。

处理方式：

1. 确认 `nodeId` 是否唯一。
2. 确认 `base-port` 配置是否被覆盖。
3. 检查端口占用。
4. 必要时为不同环境分配独立端口段。

## 13. 当前能力边界和后续演进

当前能力边界：

- MDC 是实时推送，不是可靠消息队列。
- 没有内置断线补发。
- 没有内置消费确认。
- 没有内置权限控制。
- 背压时当前实现选择丢弃 MDC 推送，保护撮合主链路。

后续可演进方向：

- 单独实现 result replay 服务，从 archive 按 `resultSerialNum` 回放。
- 将 MDC broadcaster 从撮合状态机热路径中解耦为独立进程。
- 暴露 dropped/broadcast/gap 指标到监控系统。
- 为下游提供标准 SDK，统一订阅、解码、去重、补偿和告警逻辑。

### 13.1 Result Replay 服务技术方案

目标：

- 为下游提供按 `resultSerialNum` 补齐缺口的能力。
- 将 MDC 的实时通知语义升级为“实时 MDC + 持久化补偿”的完整交付链路。
- 不改变撮合状态机确定性，不在撮合热路径中加入下游等待逻辑。

推荐架构：

```text
ResultRepository / ArchiveResultRepository
  -> ResultReplayService
  -> HTTP/gRPC query API
  -> downstream gap repair

MDC live stream
  -> downstream realtime consumer
  -> detect gap by resultSerialNum
  -> call replay API
  -> resume realtime processing
```

服务职责：

1. 读取 `ResultRepository` 中已持久化的 MatchResult。
2. 支持按 `resultSerialNum` 范围查询，例如 `[fromSerialNum, toSerialNum]`。
3. 支持从某个 `fromSerialNum` 开始分页拉取。
4. 返回结果必须保持 `resultSerialNum` 严格递增。
5. 不参与撮合命令处理，不反向影响 `MatchEngineClusteredService`。

建议接口：

```text
GET /result/replay?from=10001&to=10100
GET /result/replay?from=10001&limit=1000
GET /result/latest-serial-num
```

或者使用 gRPC：

```protobuf
service MatchResultReplayService {
  rpc ReplayByRange(ReplayByRangeRequest) returns (ReplayByRangeResponse);
  rpc ReplayFrom(ReplayFromRequest) returns (ReplayFromResponse);
  rpc GetLatestSerialNum(GetLatestSerialNumRequest) returns (GetLatestSerialNumResponse);
}
```

关键约束：

- `from`、`to` 必须基于 `resultSerialNum`，不要基于时间戳补偿。
- 单次返回需要限制最大条数，避免下游一次拉取过大范围影响服务稳定性。
- 返回数据应复用 MatchResult 的正式协议格式，避免定义另一套临时 JSON 业务协议。
- replay 服务只读结果仓储，不允许写撮合状态。
- 如果结果仓储存在多副本，应明确读取优先级：优先读本地完整仓储；不可用时再读归档或备份仓储。

下游补偿流程：

```text
1. MDC 收到 resultSerialNum=N
2. 本地 lastProcessed=M
3. 如果 N == M + 1，正常处理
4. 如果 N <= M，按幂等重复消息丢弃
5. 如果 N > M + 1，暂停推进实时结果
6. 调用 replay API 补齐 [M + 1, N - 1]
7. 补齐成功并校验连续后，处理当前 N
8. 更新 lastProcessed
```

### 13.2 MDC Broadcaster 解耦技术方案

目标：

- 降低撮合状态机热路径对 Aeron MDC publication 状态的感知。
- 将实时广播失败、背压、下游连接变化隔离到独立组件。
- 保持撮合主链路只负责生成和持久化 MatchResult。

推荐分阶段演进。

第一阶段：进程内解耦。

```text
CommandDispatcher
  -> resultRepository.persist(batch)
  -> enqueue ResultBatch to in-memory ring buffer
  -> async ResultBroadcastWorker
  -> Aeron MDC
```

实现要点：

- 使用有界队列或 Disruptor ring buffer。
- 队列满时仍不阻塞撮合主链路，可以丢弃实时广播任务并增加 dropped 指标。
- worker 只负责 MDC offer，不做业务计算。
- 队列元素包含 `resultSerialNum` 范围，便于日志和指标定位。
- Leader 切换时 worker 根据 `ClusterRoleHolder` 判断是否继续广播。

第二阶段：独立广播进程。

```text
MatchEngineClusteredService
  -> ResultRepository / ArchiveResultRepository
  -> ResultBroadcastRelay
  -> Aeron MDC
```

实现要点：

- `ResultBroadcastRelay` 作为独立进程或独立 Spring Boot 服务部署。
- Relay 从结果仓储按 `resultSerialNum` 增量读取。
- Relay 自己维护 `lastPublishedResultSerialNum`。
- Relay 可以在 Leader 节点本机部署，也可以作为独立可靠广播服务部署。
- 撮合节点不再直接对下游做 MDC offer。

独立进程方案的优点：

- 撮合状态机内没有 MDC offer 调用。
- 背压不会直接发生在撮合进程内。
- Relay 可以重启后从 `lastPublishedResultSerialNum + 1` 继续发布。
- 可在 Relay 内实现更丰富的限速、监控、订阅治理。

需要注意：

- 如果多个 Relay 同时运行，必须有主备或租约机制，避免重复广播。
- Relay 的发布语义仍建议保持“实时通知 + 下游幂等”，不要伪装成强可靠队列。
- Relay 读取结果仓储时必须保证顺序，不允许跨序号并发发布。

### 13.3 可观测性技术方案

目标：

- 让 MDC 链路的问题可以被量化：是否广播、是否丢弃、是否跳号、延迟多大。
- 区分撮合主链路问题、MDC publication 问题、下游消费问题。

服务端建议指标：

| 指标 | 类型 | 含义 |
| --- | --- | --- |
| `matching_result_mdc_broadcast_total` | Counter | 已成功 offer 的结果数量 |
| `matching_result_mdc_dropped_total` | Counter | 因 publication 不可用或背压丢弃的结果数量 |
| `matching_result_mdc_backpressure_total` | Counter | offer 返回 back pressure 的次数 |
| `matching_result_mdc_last_serial_num` | Gauge | 最近一次尝试广播的 `resultSerialNum` |
| `matching_result_mdc_role` | Gauge | 当前节点是否 LEADER，LEADER=1，其他=0 |
| `matching_result_mdc_publication_connected` | Gauge | publication 是否有订阅者连接 |

下游建议指标：

| 指标 | 类型 | 含义 |
| --- | --- | --- |
| `matching_result_consumer_last_serial_num` | Gauge | 下游已处理的最大 `resultSerialNum` |
| `matching_result_consumer_gap_total` | Counter | 检测到缺口次数 |
| `matching_result_consumer_duplicate_total` | Counter | 重复结果次数 |
| `matching_result_consumer_decode_error_total` | Counter | 解码失败次数 |
| `matching_result_consumer_replay_total` | Counter | 补偿拉取次数 |
| `matching_result_consumer_lag` | Gauge | 服务端最新序号与下游已处理序号差值 |

告警建议：

- `dropped_total` 持续增长：说明实时 MDC 已经不完整，需要检查下游消费和网络。
- 下游 `gap_total` 增长：说明实时链路存在丢失或切换窗口，需要检查 replay 是否补齐成功。
- `consumer_lag` 持续扩大：说明下游处理速度低于结果产生速度。
- 长时间无 MDC 但集群仍有交易请求：需要同时检查当前节点角色、publication 连接数、下游订阅地址。

### 13.4 下游 SDK 技术方案

目标：

- 避免每个下游重复实现 Aeron 订阅、SBE 解码、序号校验和补偿逻辑。
- 统一生产处理语义，降低漏做幂等和跳号检测的风险。

SDK 应提供的能力：

1. 同时订阅所有撮合节点 MDC control 端口。
2. 自动识别有效结果流，按 `resultSerialNum` 做去重。
3. 校验连续性，发现缺口后回调补偿接口。
4. 支持接入 replay API 自动补齐。
5. 暴露消费指标和关键事件回调。
6. 提供幂等处理模板，要求业务方显式确认处理成功后推进本地 offset。

建议 API 形态：

```java
MatchResultConsumer consumer = MatchResultConsumer.builder()
        .aeronDirectoryName(aeronDir)
        .streamId(1002)
        .addPublisher("10.0.1.10", 40456)
        .addPublisher("10.0.1.11", 40457)
        .addPublisher("10.0.1.12", 40458)
        .replayClient(replayClient)
        .offsetStore(offsetStore)
        .handler(result -> {
            // 下游业务逻辑：清算、资金、风控等
        })
        .build();

consumer.start();
```

SDK 内部处理模型：

```text
Aeron poll thread
  -> decode MatchResult
  -> append to ordered buffer
  -> serialNum dedupe/gap check
  -> replay missing range if needed
  -> invoke business handler in order
  -> commit offset after handler success
```

关键约束：

- SDK 的 Aeron poll 线程不能执行慢业务逻辑。
- 业务 handler 失败时不能推进 offset。
- offsetStore 至少记录 `lastProcessedResultSerialNum`。
- SDK 只能保证本地下游处理有序，不能替代服务端结果持久化。
- replay 补偿失败时应停止推进并持续告警，不能跳过缺口继续处理。

### 13.5 推荐落地顺序

建议按风险收益分阶段推进：

1. 先补齐 Result Replay 服务，解决 MDC 丢失后的补偿闭环。
2. 暴露服务端和下游核心指标，建立 dropped/gap/lag 告警。
3. 提供标准下游 SDK，统一订阅、解码、去重、补偿逻辑。
4. 再考虑把 MDC broadcaster 从撮合进程中解耦为 Relay，进一步降低热路径耦合。

第一阶段完成后，生产交付语义可以从“仅实时广播”提升为：

```text
持久化结果作为事实账本
MDC 作为低延迟通知
Replay 作为缺口补偿
SDK 作为下游标准消费入口
```
