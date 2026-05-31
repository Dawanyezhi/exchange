# 交易系统 ID 与序号设计

## 1. 文档目标

本文总结交易系统里常见的 ID 和序号，包括：

- `clientOrderId`
- `orderId`
- `requestSeq`
- `resultSerialNum`
- `symbolSeq` / `bookSeq`
- `matchId`
- `orderVersion` / `orderEventSeq`
- `clearingId`
- `ledgerSeq`
- `ledgerTxId`

核心目标是回答三个问题：

```text
1. 这个 ID 代表什么业务事实？
2. 应该由哪个模块生成？
3. 下游应该用它做什么幂等、排序或追踪？
```

生产系统里不要试图用一个全局序号解决所有问题。订单、撮合、行情、清算、账本各自需要不同粒度的 ID 和顺序。

## 2. 总览表

| 名称 | 推荐生成模块 | 唯一范围 | 主要用途 |
| --- | --- | --- | --- |
| `clientOrderId` | 客户端 / SDK / 外部交易系统 | `accountId + clientOrderId` 唯一 | 客户端下单幂等、防重复提交 |
| `orderId` | OMS | 全局唯一或分片全局唯一 | 订单生命周期主键 |
| `requestSeq` / `commandSeq` | Gateway / Sequencer / Aeron Cluster Log | 全局或分片内唯一 | 内部请求定序、回放、审计 |
| `resultSerialNum` | Matching Result Event Log | 全局或撮合分片内唯一 | 撮合结果事件顺序、下游消费幂等 |
| `symbolSeq` / `bookSeq` | Matching / OrderBook | 单个 symbol 内连续 | 盘口增量、行情恢复、订单簿回放 |
| `matchId` | Matching Engine | 全局唯一或 `symbol + localMatchSeq` 唯一 | 一笔真实成交的业务 ID、清算幂等 |
| `fillId` | Matching 或 Clearing | 单账户单成交方向唯一 | 用户成交明细、maker/taker 双边回报 |
| `orderVersion` / `orderEventSeq` | OMS | 单个 order 内连续 | 订单状态版本、乱序保护 |
| `clearingId` | Clearing | 全局唯一或清算分片内唯一 | 清算批次/清算指令追踪 |
| `ledgerSeq` | Ledger | 全局或账本分片内连续 | 资金流水顺序、账本审计 |
| `accountLedgerSeq` | Ledger | 单账户内连续 | 账户资金流水查询和对账 |
| `ledgerTxId` | Ledger | 一组流水唯一 | 一次资产变更事务分组 |

## 3. clientOrderId

### 3.1 含义

`clientOrderId` 是客户端生成的订单幂等 ID。

它代表：

```text
客户端的一次下单意图
```

不是每次 HTTP 请求都生成一个新的 `clientOrderId`。

### 3.2 生成模块

推荐由客户端、SDK、交易机器人或外部交易系统生成。

常见生成方式：

```text
UUIDv7
ULID
Snowflake
业务前缀 + 日期 + 本地递增序号
```

示例：

```text
web-20260531-000001
api-btcusdt-01JZABC...
```

### 3.3 幂等规则

服务端 OMS 应保证：

```text
unique(accountId, clientOrderId)
```

如果客户端超时后重试，必须复用同一个 `clientOrderId`：

```text
第一次请求:
  accountId = 10001
  clientOrderId = abc-001

网络超时后重试:
  accountId = 10001
  clientOrderId = abc-001
```

OMS 发现该组合已存在时，应返回原订单状态，而不是创建第二张订单。

### 3.4 错误做法

不要在每次重试时生成新的 `clientOrderId`：

```text
第一次请求: clientOrderId = abc-001
重试请求:   clientOrderId = abc-002
```

这样服务端无法区分：

```text
这是用户真的下了第二单
还是第一单网络超时后的重试
```

## 4. orderId

### 4.1 含义

`orderId` 是交易所内部订单 ID。

它代表：

```text
交易所系统里一张订单的生命周期主键
```

### 4.2 推荐生成模块

推荐由 OMS 生成。

原因：

- OMS 是订单生命周期主控
- OMS 负责订单状态机
- OMS 负责订单查询视图
- OMS 负责 clientOrderId 幂等
- OMS 需要在订单进入风控、撮合前建立订单事实

### 4.3 和 clientOrderId 的区别

| 字段 | 生成方 | 作用 |
| --- | --- | --- |
| `clientOrderId` | 客户端 | 客户端重试幂等和外部追踪 |
| `orderId` | OMS | 交易所内部订单生命周期主键 |

同一个订单通常同时有：

```text
clientOrderId = api-001
orderId = 90000001
```

## 5. requestSeq / commandSeq

### 5.1 含义

`requestSeq` 或 `commandSeq` 表示交易所内部接收到的请求顺序。

它代表：

```text
交易所内部按什么顺序处理请求
```

### 5.2 推荐生成模块

可以由以下任一位置生成，取决于架构：

| 方案 | 说明 |
| --- | --- |
| Gateway / Session | 接入层收到请求后分配 |
| 独立 Sequencer | 所有请求先进入定序器 |
| Journal / Log | 写入日志的 offset 作为顺序 |
| Aeron Cluster Log | 集群复制日志顺序作为状态机执行顺序 |

当前项目里，`counter` 在发送前分配 `serialNum`，`matching` 用 `SerialNumValidator` 校验连续性。

生产上不要只信客户端提供的序号。客户端序号主要用于会话可靠性；交易所内部应生成或确认自己的权威业务顺序。

## 6. resultSerialNum

### 6.1 含义

`resultSerialNum` 是撮合结果事件流里的全局事件序号。

它代表：

```text
某个撮合结果事件在结果流中的顺序
```

它不是一笔成交的业务 ID。

### 6.2 推荐生成模块

推荐由 Matching Result Event Log 或撮合结果事件生成器生成。

当前项目中已经有类似设计：

```text
MatchResult.header.resultSerialNum
```

### 6.3 用途

适合用于：

- 结果事件持久化
- 全量事件回放
- 下游消费 offset
- 审计追踪
- 判断结果事件是否丢失
- OMS / Clearing / MarketData 消费幂等

### 6.4 不建议替代 matchId

`resultSerialNum` 表示事件顺序；`matchId` 表示一笔真实成交。

短期内，如果系统保证：

```text
一个 MatchOrderResult 严格等于一笔成交
```

可以临时用 `resultSerialNum` 作为成交幂等键。

长期生产设计不建议这样做，因为一笔成交可能派生多个事件：

```text
MatchEvent
OrderEvent for maker
OrderEvent for taker
MarketData trade event
Clearing instruction
Ledger entries
```

这些事件可以有不同 `resultSerialNum`，但应该共享同一个 `matchId`。

## 7. symbolSeq / bookSeq

### 7.1 含义

`symbolSeq` 或 `bookSeq` 是单个交易对内部的订单簿变更序号。

它代表：

```text
某个 symbol 的订单簿状态变化顺序
```

例如：

```text
BTC-USDT symbolSeq = 1001
BTC-USDT symbolSeq = 1002
ETH-USDT symbolSeq = 5001
```

### 7.2 推荐生成模块

推荐由撮合引擎的 `OrderBook` 生成。

原因：

- 撮合引擎是订单簿状态源头
- `symbolSeq` 表示订单簿变更顺序
- 行情系统只是消费撮合事件并发布行情
- 不应由行情系统根据 `resultSerialNum` 临时推导权威盘口序号

实现方式：

```text
OrderBook:
  symbol
  lastSymbolSeq

每次该 symbol 的订单簿发生变化:
  lastSymbolSeq++
  event.symbolSeq = lastSymbolSeq
```

### 7.3 为什么不能只用 resultSerialNum

假设全局事件流：

```text
resultSeq=1 BTC-USDT update
resultSeq=2 ETH-USDT update
resultSeq=3 SOL-USDT update
resultSeq=4 BTC-USDT update
```

如果 BTC 客户端只订阅 BTC，它看到：

```text
1, 4
```

中间缺失 `2, 3` 并不是 BTC 缺包，而是其他交易对事件。

所以行情增量恢复需要：

```text
BTC-USDT symbolSeq = 1, 2, 3...
```

这类 per-symbol 序号更适合订单簿快照 + 增量。

## 8. matchId

### 8.1 含义

`matchId` 是一笔真实撮合成交的唯一 ID。

一笔 match 通常表示：

```text
一个 taker order
和一个 maker order
在某个价格
成交了某个数量
```

示例：

```text
matchId = 880001
symbol = BTC-USDT
makerOrderId = 1001
takerOrderId = 2001
price = 60000
baseQty = 0.1
quoteQty = 6000
```

### 8.2 推荐生成模块

推荐由 Matching Engine 生成。

原因：

- 撮合引擎知道真实成交发生时刻
- 撮合引擎知道 maker/taker
- 撮合引擎知道成交价格、数量和顺序
- 清算应以撮合事实为输入

### 8.3 生成方式

单撮合实例：

```text
long nextMatchId++
```

按 symbol 分片：

```text
matchId = shardId + localMatchSeq
```

或者使用复合唯一键：

```text
(symbol, symbolMatchSeq)
```

生产中不一定必须有一个中心化全局 ID 服务。按撮合分片生成局部连续 ID，再通过 `shardId / symbol` 组合成全局唯一，通常更可扩展。

### 8.4 清算幂等

清算模块建议使用：

```text
matchId + accountId + asset + entryType
```

作为业务幂等维度，防止同一成交重复入账。

也可以分成双边 fill：

```text
makerFillId = matchId + makerOrderId
takerFillId = matchId + takerOrderId
```

## 9. fillId

### 9.1 含义

`fillId` 是用户视角的一条成交明细 ID。

一笔 `matchId` 通常对应买卖双方各一条 fill：

```text
matchId = 880001

buyer fill:
  fillId = 880001-B
  orderId = buyerOrderId

seller fill:
  fillId = 880001-S
  orderId = sellerOrderId
```

### 9.2 推荐生成模块

可以由 Matching 生成，也可以由 Clearing / OMS 在消费 match 事件时派生。

推荐原则：

- 如果成交回报要立即从撮合事件发布，Matching 可以生成 fillId
- 如果 fill 是用户订单视图的一部分，OMS 可以基于 matchId 派生
- 清算不能改变成交事实，只能消费 fill / match 做资产变化

## 10. orderVersion / orderEventSeq

### 10.1 含义

`orderVersion` 或 `orderEventSeq` 是单张订单内部的状态版本。

它代表：

```text
这张订单的第几次状态变化
```

示例：

```text
orderId = 10001

version 1: NEW
version 2: PARTIALLY_FILLED
version 3: PARTIALLY_FILLED
version 4: FILLED
```

### 10.2 推荐生成模块

推荐由 OMS 生成和维护。

原因：

- OMS 是订单生命周期主控
- OMS 负责把撮合事件聚合成订单状态
- OMS 提供订单查询视图
- OMS 需要防止订单事件乱序覆盖

### 10.3 用途

适合用于：

- 订单状态乐观锁
- 防止乱序事件覆盖新状态
- 用户订单查询版本
- 回报补偿
- 订单状态回放

## 11. clearingId

### 11.1 含义

`clearingId` 是清算指令或清算批次 ID。

它代表：

```text
清算模块对某笔成交或某批成交生成的一次资产变更计算
```

### 11.2 推荐生成模块

推荐由 Clearing 模块生成。

清算输入是：

```text
matchId
成交价格
成交数量
maker/taker
冻结记录
费率规则
```

清算输出是：

```text
资产变更指令
手续费
冻结释放
账本写入请求
```

## 12. ledgerSeq

### 12.1 含义

`ledgerSeq` 是账本流水顺序号。

它代表：

```text
一条资金流水在账本中的顺序
```

### 12.2 推荐生成模块

推荐由 Ledger 模块生成。

不是撮合生成，也不是清算生成。

职责链路：

```text
Matching:
  生成 matchId 和成交事实

Clearing:
  根据 matchId 计算资产变化

Ledger:
  写入资金流水
  分配 ledgerSeq

Account:
  根据账本或账户变更事件更新余额视图
```

### 12.3 账本相关 ID

生产账本通常不止一个序号：

| 字段 | 说明 |
| --- | --- |
| `ledgerSeq` | 单条流水的全局或分片顺序 |
| `accountLedgerSeq` | 单账户内资金流水顺序 |
| `ledgerEntryId` | 单条流水唯一 ID |
| `ledgerTxId` | 一组相关流水的事务 ID |

例如一笔买入成交可能产生：

```text
ledgerTxId = TX10001

ledgerSeq=80001 USDT frozen -30000
ledgerSeq=80002 BTC available +1
ledgerSeq=80003 USDT fee -30
```

这些流水属于同一个 `ledgerTxId`。

## 13. 推荐事件字段组合

### 13.1 下单命令

```text
PlaceOrderCommand:
  requestSeq
  clientOrderId
  orderId
  accountId
  symbol
  side
  orderType
  price
  baseQty / quoteAmount
```

### 13.2 撮合成交事件

```text
MatchEvent:
  resultSerialNum
  symbol
  symbolSeq
  matchId
  makerOrderId
  takerOrderId
  makerAccountId
  takerAccountId
  tradePrice
  tradeBaseQty
  tradeQuoteAmount
  makerRemainingQty
  takerRemainingQty
  makerStatusAfter
  takerStatusAfter
```

### 13.3 OMS 订单事件

```text
OrderEvent:
  orderId
  orderVersion
  sourceResultSerialNum
  sourceMatchId
  statusBefore
  statusAfter
  filledQtyDelta
  cumulativeFilledQty
  remainingQty
```

### 13.4 清算指令

```text
ClearingInstruction:
  clearingId
  sourceMatchId
  sourceResultSerialNum
  accountId
  asset
  amount
  entryType
  feeAmount
  holdReleaseAmount
```

### 13.5 账本流水

```text
LedgerEntry:
  ledgerSeq
  accountLedgerSeq
  ledgerTxId
  sourceType
  sourceId
  accountId
  asset
  amount
  beforeAvailable
  afterAvailable
  beforeFrozen
  afterFrozen
```

## 14. 最佳实践总结

### 14.1 生成职责

```text
Client / SDK:
  clientOrderId

OMS:
  orderId
  orderVersion / orderEventSeq

Gateway / Sequencer / Cluster Log:
  requestSeq / commandSeq

Matching / OrderBook:
  resultSerialNum
  symbolSeq / bookSeq
  matchId

Clearing:
  clearingId

Ledger:
  ledgerSeq
  accountLedgerSeq
  ledgerTxId
```

### 14.2 不要混用的概念

不要把：

```text
resultSerialNum
```

长期当成：

```text
matchId
```

因为前者是事件顺序，后者是成交业务事实。

不要把：

```text
clientOrderId
```

当成：

```text
orderId
```

因为前者由客户端生成，后者由交易所内部管理。

不要把：

```text
symbolSeq
```

交给行情系统临时推导。

因为订单簿变更的权威源头是撮合引擎。

### 14.3 推荐原则

```text
谁拥有事实，谁生成对应 ID。
谁维护状态，谁生成对应版本。
谁写入账本，谁生成账本序号。
谁消费事件，谁用源 ID 做幂等。
```

一句话总结：

```text
clientOrderId 保证客户端重试幂等；
orderId 管理订单生命周期；
requestSeq 管理内部请求顺序；
resultSerialNum 管理撮合结果事件顺序；
symbolSeq 管理单交易对盘口顺序；
matchId 标识一笔真实成交；
orderVersion 管理单订单状态版本；
ledgerSeq 管理资金流水顺序。
```
