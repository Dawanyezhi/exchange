# Outbox Dispatcher 生产实现方案

## 1. 背景

OMS、风控、账户和撮合引擎通常不是同一个进程。

下单、改单、撤单请求在进入撮合引擎前，通常已经完成：

```text
clientOrderId 幂等检查
订单状态创建或更新
风控校验
资金或仓位冻结
生成发送给撮合的标准化命令
```

如果这些状态已经写入数据库，但发送撮合命令时系统宕机，就会出现：

```text
订单已经进入 PENDING_MATCH
资金已经冻结
但撮合引擎永远没有收到命令
```

Outbox 的作用就是把“业务状态变更”和“待发送撮合命令”放在同一个可靠事务里，让系统恢复后可以继续投递。

## 2. 全局 sequencerId 是否可行

使用全局 `sequencerId` 管理所有发送给 Matching 的命令是可行的，尤其适合当前项目这种单撮合集群、单状态机模型。

链路可以是：

```text
OMS / Risk / Account DB Transaction
  -> 订单状态变更
  -> 资金冻结或释放计划
  -> 获取全局 sequencerId
  -> 写入 matching_command_outbox
  -> commit

Outbox Dispatcher
  -> 按 sequencerId 严格递增投递给 Matching

Matching
  -> Aeron Cluster 对命令做最终日志复制和状态机执行
  -> SerialNumValidator 校验 sequencerId 是否连续
```

这种模型的优点：

- 请求端不需要区分 symbol。
- Matching 侧只维护一个 `lastSerialNum`。
- 恢复逻辑简单，快照里只需要保存一个最大已处理序号。
- 当前项目已经采用类似模式，`SerialNumValidator` 校验全局连续序号，快照恢复时恢复 `lastSerialNum`。

不足也很明确：

- 所有交易对共享一个顺序，会降低横向扩展能力。
- 任意一个序号缺口都会阻塞后续所有 symbol。
- 如果未来拆成多个撮合分片，全局连续校验会让不同分片相互等待。
- BTC-USDT 的缺口可能导致 ETH-USDT、SOL-USDT 的命令都无法继续处理。

因此生产建议是分阶段：

```text
阶段 1：单撮合集群
  使用全局 sequencerId，简单、清晰、可恢复。

阶段 2：多撮合分片
  升级为 globalCommandId + partitionId + partitionSeq。

阶段 3：多活或跨机房
  再引入更明确的 shard、epoch、leaderTerm、sourceId。
```

当前项目如果目标是先形成完整交易系统，使用全局 `sequencerId` 是合理的。

## 3. sequencerId 应该在哪里生成

`sequencerId` 不应该在 Dispatcher 临时扫描 outbox 时才生成。

推荐在写入 `matching_command_outbox` 前生成，或者和 outbox insert 在同一个数据库事务里生成。

推荐链路：

```text
Gateway
  -> OMS 接收请求
  -> clientOrderId 幂等检查
  -> 风控校验
  -> 账户冻结
  -> 生成 sequencerId
  -> 写订单状态
  -> 写 matching_command_outbox
  -> commit
```

原因是：

```text
sequencerId 是这条撮合命令的业务顺序身份。
只要订单状态和冻结已经提交，就必须能恢复出它应该被第几个发送到 Matching。
```

如果 Dispatcher 扫描时才生成序号，OMS 宕机恢复、多个 Dispatcher 竞争、重复扫描和重试都会让顺序难以证明。

## 4. Outbox 表设计

示例表：

```sql
create table matching_command_outbox (
    id bigint primary key,
    sequencer_id bigint not null,
    command_id varchar(64) not null,
    order_id bigint not null,
    client_order_id varchar(64),
    account_id bigint not null,
    symbol varchar(32) not null,
    cmd_type varchar(16) not null,
    payload_json text not null,
    status varchar(16) not null,
    send_attempts int not null default 0,
    last_error varchar(512),
    created_at timestamp not null,
    updated_at timestamp not null,
    sent_at timestamp,
    acked_at timestamp,
    unique (sequencer_id),
    unique (command_id)
);
```

核心字段：

| 字段 | 含义 |
| --- | --- |
| `sequencer_id` | 发给 Matching 的全局连续序号 |
| `command_id` | 命令幂等 ID，一次下单、撤单、改单各有自己的 commandId |
| `order_id` | 订单 ID |
| `cmd_type` | `PLACE`、`AMEND`、`CANCEL` |
| `payload_json` | 待编码成 SBE 的命令内容，生产中可用二进制或结构化列 |
| `status` | 投递状态 |
| `send_attempts` | 重试次数 |
| `acked_at` | 收到 Matching 结果后的确认时间 |

状态建议：

```text
NEW       已提交，等待发送
SENDING   正在发送，防止多个 Dispatcher 重复抢占
SENT      已发送给 Aeron Cluster，但尚未收到 Matching 结果
ACKED     已收到 Matching 结果
FAILED    多次发送失败，需要人工或补偿处理
```

生产中不要只用 `SENT` 表示完成。

真正完成应该以 Matching 的结果事件为准：

```text
MatchResult.requestSerialNum == outbox.sequencer_id
```

## 5. 事务写入流程

以下操作应在一个数据库事务里完成：

```text
1. 校验 clientOrderId 是否重复。
2. 创建订单或更新订单状态。
3. 执行风控检查。
4. 执行账户冻结。
5. 获取 sequencerId。
6. 写入 matching_command_outbox。
7. 提交事务。
```

伪代码：

```java
@Transactional
public long submitPlaceOrder(PlaceOrderCommand cmd) {
    checkClientOrderId(cmd.accountId(), cmd.clientOrderId());

    long orderId = orderIdGenerator.nextId();
    long sequencerId = matchingSequencer.next();
    String commandId = "PLACE-" + orderId;

    orderRepository.insert(orderId, cmd, "PENDING_MATCH");
    accountService.freeze(cmd.accountId(), cmd.quoteCurrency(), cmd.lockedQuoteAmount());

    outboxRepository.insert(new OutboxRecord(
            sequencerId,
            commandId,
            orderId,
            cmd.symbol(),
            "PLACE",
            encodePayload(cmd, orderId, sequencerId),
            "NEW"
    ));

    return orderId;
}
```

这里的关键不是代码形式，而是事务边界：

```text
订单状态、冻结状态、outbox 记录必须一起成功或一起失败。
```

## 6. Dispatcher 运行模型

Outbox Dispatcher 不应该是普通的秒级定时任务。

生产中更推荐常驻循环：

```text
while running:
  record = load next sendable outbox by sequencer_id
  if record exists:
      mark SENDING
      send to Aeron Cluster
      mark SENT
      continue
  else:
      short backoff or wait for signal
```

延迟敏感系统中，常见做法：

- DB commit 后本地唤醒 Dispatcher。
- Dispatcher 空闲时 `parkNanos` 或 1ms 级 backoff。
- 有数据时持续 drain，不做固定秒级等待。
- 如果数据量大，按 batch 拉取，但发送仍必须按 `sequencer_id` 顺序。

不推荐：

```text
@Scheduled(fixedDelay = 1000)
每秒扫描一次待发送订单
```

这会给下单链路直接增加最高 1 秒的额外延迟。

## 7. Dispatcher 发送顺序

全局 `sequencerId` 模型下，Dispatcher 必须按严格递增顺序发送：

```sql
select *
from matching_command_outbox
where status in ('NEW', 'SENT')
  and sequencer_id = :nextSequencerId
order by sequencer_id
limit 1;
```

Dispatcher 内部维护：

```text
nextSequencerIdToSend
```

恢复时不能只从内存恢复，应从数据库和 Matching 确认点恢复：

```text
maxAckedSequencerId = 已收到 Matching 结果的最大连续 sequencerId
nextSequencerIdToSend = maxAckedSequencerId + 1
```

如果 `1001` 已发送但未确认，恢复后可以重发 `1001`。

要求 Matching 支持幂等：

```text
重复 sequencerId 或 commandId 不应再次改变订单簿。
```

## 8. 发送和确认流程

发送流程：

```text
1. 读取 nextSequencerId 对应 outbox。
2. 抢占记录：NEW/SENT -> SENDING。
3. 编码为 SBE。
4. AeronCluster.offer。
5. offer 成功后更新 status = SENT。
6. 继续发送下一条，或等待确认策略。
```

确认流程：

```text
1. OMS/Dispatcher 订阅 MatchResult。
2. 根据 requestSerialNum 找到 outbox。
3. 将 outbox 更新为 ACKED。
4. 更新订单状态。
5. 如果是拒绝结果，执行冻结释放或补偿。
```

注意：

```text
AeronCluster.offer 成功只代表命令交给 Aeron Client，并不等于订单已被撮合。
订单最终状态必须以 MatchResult 为准。
```

## 9. 是否等待 ACK 后再发送下一条

有两种方案。

方案 A：严格等待 ACK。

```text
send 1001
wait ACK 1001
send 1002
```

优点：

- 实现简单。
- 恢复容易。
- 不容易出现大量 in-flight 请求。

缺点：

- 吞吐和延迟较差。

方案 B：允许窗口内流水线发送。

```text
send 1001
send 1002
send 1003
...
window <= N
```

优点：

- 吞吐高。
- 更适合低延迟链路。

缺点：

- 需要维护连续 ACK。
- 需要处理 1001 未 ACK、1002 已 ACK 的情况。
- 恢复逻辑更复杂。

推荐阶段方案：

```text
第一阶段：严格顺序发送，可以不等待 ACK 但必须记录 SENT，Matching 侧严格校验连续序号。
第二阶段：增加 in-flight window，例如 1024。
第三阶段：按撮合分片拆多个 Dispatcher。
```

## 10. 重试和恢复

Dispatcher 可能在这些位置宕机：

```text
已抢占 SENDING，未发送
已发送 Aeron，未更新 SENT
已更新 SENT，Matching 未处理
Matching 已处理，OMS 未收到 MatchResult
```

恢复策略：

```text
SENDING 超时 -> 回到 NEW 或 RETRY
SENT 未 ACK -> 从最小未 ACK sequencerId 开始重发
ACKED -> 不再发送
FAILED -> 人工处理或补偿任务处理
```

这意味着投递语义通常是：

```text
at-least-once 投递
Matching 幂等消费
下游按 resultSerialNum / requestSerialNum 幂等处理
```

不要指望分布式链路天然 exactly-once。

## 11. 撮合侧幂等：能否只使用全局 sequencerId

使用全局 `sequencerId` 做撮合幂等是合理的，当前项目已经有类似基础：

```text
AbstractRequest.serialNum
SerialNumValidator.lastSerialNum
快照恢复 lastSerialNum
Aeron Cluster raft log 回放
```

如果输入严格满足：

```text
每个 sequencerId 只对应一条命令
命令按 sequencerId 严格 +1 到达
小于等于 lastSerialNum 的命令一律视为重复
大于 lastSerialNum + 1 的命令视为缺口
```

那么撮合侧可以用 `sequencerId` 做主幂等键。

但生产中仍建议保留 `commandId`，原因是：

```text
sequencerId 解决的是撮合输入流顺序和重复投递问题。
commandId 解决的是业务命令身份问题。
orderId 解决的是订单身份问题。
```

它们不是完全等价。

## 12. 只用 sequencerId 的不足

### 12.1 无法表达同一订单的多次操作

同一个订单可能有：

```text
PLACE orderId=1, sequencerId=100
AMEND orderId=1, sequencerId=120
CANCEL orderId=1, sequencerId=135
```

`sequencerId` 可以区分顺序，但不能直接表达这些命令是不是同一次业务请求的重试。

`commandId` 可以表达：

```text
PLACE-1
AMEND-1-版本2
CANCEL-1-请求5
```

### 12.2 无法防止上游错误复用业务命令

如果上游 bug 导致同一个撤单请求被分配了两个不同 sequencerId：

```text
CANCEL commandId=C1, sequencerId=200
CANCEL commandId=C1, sequencerId=201
```

只看 `sequencerId`，撮合会认为这是两条不同命令。

有 `commandId`，撮合可以识别第二条是重复命令。

### 12.3 不利于问题排查

排查问题时，通常要从用户请求追踪到撮合结果：

```text
clientOrderId
orderId
commandId
sequencerId
requestSerialNum
resultSerialNum
matchId
ledgerSeq
```

如果只有 `sequencerId`，排查会过度依赖单条输入流，不利于跨系统关联。

### 12.4 不利于未来拆分撮合分片

全局 `sequencerId` 在单撮合状态机里简单。

一旦未来变成：

```text
matching-shard-1
matching-shard-2
matching-shard-3
```

就需要重新设计：

```text
globalCommandId
partitionId
partitionSeq
```

如果一开始保留 `commandId`，未来迁移成本会低很多。

## 13. PLACE、AMEND、CANCEL 的幂等建议

### 13.1 PLACE

幂等键：

```text
sequencerId
commandId
orderId
```

规则：

```text
同一个 orderId 不能重复创建。
同一个 commandId 重试应返回同一处理结果。
小于等于 lastSerialNum 的请求不再改变订单簿。
```

### 13.2 AMEND

幂等键：

```text
sequencerId
commandId
orderId
expectedOrderVersion
```

规则：

```text
同一个 commandId 重试不应再次修改订单。
expectedOrderVersion 不匹配时拒绝或返回已过期。
订单已成交、已取消、已过期时不能继续改单。
```

### 13.3 CANCEL

幂等键：

```text
sequencerId
commandId
orderId
```

规则：

```text
同一个 commandId 重试返回同一撤单结果。
订单已经是终态时，不应重复生成成功撤单事件。
不同 commandId 撤同一个终态订单，应返回 already terminal 或 cancel rejected。
```

否则可能导致下游重复释放冻结资金。

## 14. 和当前项目的对应关系

当前项目中：

```text
counter 生成 serialNum
matching 的 SerialNumValidator 校验 serialNum 连续
SnapshotManager 快照恢复 lastSerialNum
Aeron Cluster 负责命令日志、复制和状态机回放
MatchResult 带 requestSerialNum 和 resultSerialNum
```

这已经具备单撮合集群的基础顺序模型。

后续如果补 OMS / Account / Clearing，建议：

```text
1. 将 serialNum 的生成从 counter 下沉到 OMS / Sequencer 服务。
2. OMS 数据库事务内写入 matching_command_outbox。
3. Outbox Dispatcher 按 serialNum 严格投递给 matching。
4. Matching 继续用 SerialNumValidator 校验连续性。
5. MatchResult 回流 OMS 后更新 outbox ACK 和订单状态。
```

## 15. 推荐落地方案

在当前项目阶段，推荐使用：

```text
全局 sequencerId
全局单 Dispatcher
Matching 全局连续性校验
commandId 作为业务幂等补充
orderId 作为订单身份
resultSerialNum 作为撮合结果事件序号
```

不要一开始就强行拆 `symbolSeq`。

但接口和表结构应预留：

```text
partition_id
partition_seq
leader_epoch
source_service
command_id
```

这样未来从单撮合集群升级到多撮合分片时，不需要推翻全部链路。

## 16. 一句话总结

你的疑问合理。

全局 `sequencerId` 可以作为当前项目的生产化第一阶段方案；它简单、可恢复、和现有 `SerialNumValidator` / snapshot 设计一致。

它的主要不足不是正确性，而是扩展性、故障隔离和跨系统业务幂等表达能力。

因此生产实践上应保留 `commandId`、`orderId`、`resultSerialNum` 等独立 ID，不要让全局 `sequencerId` 同时承担顺序、业务身份、订单身份和事件身份。
