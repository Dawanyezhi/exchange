# Result Publisher 与 Replay 生产方案

本文说明当前项目完成广播职责迁移后的生产边界。

## 1. 当前边界

`matching` 进程只承担撮合状态机职责：

```text
接收 Aeron Cluster 命令
执行单线程撮合状态机
生成 MatchResult
追加写入 Aeron Archive result log
```

`matching` 不再内置 MDC 广播，也不提供 replay 查询接口。

实时推送和历史补偿由后续独立服务承担：

```text
result-publisher / result-relay-service
  -> 从 Aeron Archive result log 读取
  -> 按 resultSerialNum 顺序推送 MDC / MQ / gRPC stream
  -> 提供 replay 查询
  -> 维护自身发布位点
```

## 2. Matching 侧职责

核心代码位置：

- `src/main/java/com/laser/exchange/matching/resultLog/ResultLogWriter.java`
- `src/main/java/com/laser/exchange/matching/resultLog/ArchiveResultLogWriter.java`
- `src/main/java/com/laser/exchange/matching/cluster/CommandDispatcher.java`

`CommandDispatcher.flushAndPersist()` 的语义收敛为：

```text
MatchResult batch
  -> resultLogWriter.append(batch)
  -> Aeron Archive result log
```

这里没有 MDC offer，没有下游等待，也没有 replay 读取。

## 3. Archive 初始化要求

`ArchiveResultLogWriter` 是 matching 内唯一生产结果持久化实现。

要求：

- Archive 初始化失败时 matching 启动失败。
- 不降级到内存实现。
- 不在生产 main 代码保留内存结果仓储实现。
- `offer()` 写入失败遇到关闭、越界等不可恢复错误时直接抛异常。

当前配置：

```properties
laser.matching.result-log.channel=aeron:ipc
laser.matching.result-log.stream-id=1001
```

## 4. Result Publisher 职责

后续独立 `result-publisher` 服务应负责：

1. 连接 Aeron Archive。
2. 定位 result log recording。
3. 从 `lastPublishedResultSerialNum + 1` 开始顺序读取。
4. 解码 SBE `MatchResult`。
5. 按 `resultSerialNum` 连续推送实时通道。
6. 维护 `lastPublishedResultSerialNum` checkpoint。
7. 暴露 replay 查询接口。

推荐接口：

```text
GET /result/replay?from=10001&to=10100
GET /result/replay?from=10001&limit=1000
GET /result/latest-serial-num
```

下游发现缺口时，只查询 `result-publisher`，不回调 matching 状态机。

## 5. 下游消费要求

下游必须按 `resultSerialNum` 做顺序、幂等和缺口检测：

```text
N == last + 1  正常处理
N <= last      重复消息，幂等丢弃
N > last + 1   暂停推进，调用 replay 补齐缺口
```

每个下游模块保存自己的消费位点：

```text
consumerName + lastConsumedResultSerialNum
```

例如：

- OMS 订单视图。
- Clearing 清算。
- MarketData 行情。
- Audit 审计。

## 6. 生产语义

当前迁移后的语义：

- 可靠事实源是 Aeron Archive result log。
- matching 不等待任何下游。
- matching 不承担实时广播背压。
- replay 查询不进入撮合状态机。
- result-publisher 可独立重启并从 checkpoint 继续。

仍需后续补齐：

- Archive durable commit：等待 recording position 追上 publication position。
- resultSerialNum 到 recording position 的索引或 checkpoint。
- result-publisher 服务实现。
- replay API。
- 下游 consumer checkpoint。
- result log 与 snapshot 边界校验。
