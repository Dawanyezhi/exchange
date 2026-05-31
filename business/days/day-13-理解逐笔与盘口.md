# Day 13：理解逐笔与盘口

## 1. 今天的学习目标

今天的目标是理解 `MBO`、`MBP`、`L2`、逐笔成交和盘口之间的关系。

学完 Day 13 后，需要能回答：

- 什么是盘口
- 什么是 L1、L2、MBP、MBO
- MBO 和 MBP 的区别是什么
- 为什么逐笔订单可以帮助重建盘口
- 从增量逐笔消息重建订单簿至少需要哪些字段
- 为什么执行策略通常更偏爱逐笔级别行情

参考资料：

- CME Market by Order (MBO) FAQ：https://www.cmegroup.com/articles/faqs/market-by-order-mbo.html
- Nasdaq TotalView-ITCH 5.0 Specification：https://nasdaqtrader.com/content/technicalsupport/specifications/dataproducts/NQTVITCHSpecification.pdf
- Coinbase Exchange WebSocket Feed：https://docs.cdp.coinbase.com/exchange/websocket-feed/overview

## 2. 盘口是什么

盘口是某个交易对当前可见的买卖挂单状态。

最简盘口：

```text
BTC-USDT

Best Ask: 30010, 2 BTC
Best Bid: 30000, 1 BTC
```

多档盘口：

```text
Ask:
30030  4 BTC
30020  3 BTC
30010  2 BTC

Bid:
30000  1 BTC
29990  5 BTC
29980  2 BTC
```

盘口是交易者观察市场流动性的主要入口。

## 3. L1、L2、MBP、MBO

### 3.1 L1

`L1` 通常表示最优买卖价和最新成交等最基础行情。

常见字段：

```text
bestBidPrice    买一价
bestBidQty      买一数量，当前买一价位上的可见挂单总数量
bestAskPrice    卖一价
bestAskQty      卖一数量，当前卖一价位上的可见挂单总数量
lastPrice       最新成交价
lastQty         最新成交数量
```

L1 能告诉你当前最优价，但看不到更深档位。

### 3.2 L2

`L2` 通常表示多档深度。

例如前 5 档、前 20 档、前 200 档：

```text
Ask:
30030  4
30020  3
30010  2

Bid:
30000  1
29990  5
29980  2
```

L2 通常是按价格档聚合后的盘口。

### 3.3 MBP

`MBP` 是 `Market By Price`。

它按价格档聚合数量：

```text
price = 30000
totalQty = 10 BTC
orderCount = 5
```

MBP 看不到这个价格档内部每张订单的先后队列。

### 3.4 MBO

`MBO` 是 `Market By Order`。

它展示订单级别的明细：

```text
price = 30000
orderId = A, qty = 2 BTC, priority = 1
orderId = B, qty = 3 BTC, priority = 2
orderId = C, qty = 5 BTC, priority = 3
```

MBO 能看到同一个价格档内部的订单队列。

## 4. L2 行情 vs MBO 对比图

```text
同一个价格档：30000

L2 / MBP 看到：
  30000  10 BTC

MBO 看到：
  30000  order A 2 BTC
  30000  order B 3 BTC
  30000  order C 5 BTC
```

对比表：

| 维度 | L2 / MBP | MBO |
| --- | --- | --- |
| 粒度 | 价格档 | 单笔订单 |
| 是否能看到队列 | 不能 | 可以 |
| 数据量 | 较小 | 较大 |
| 重建难度 | 较低 | 较高 |
| 策略价值 | 看深度和价差 | 看排队、撤单、真实流动性 |
| 常见用途 | 普通行情展示、基础策略 | 高频执行、排队估算、微观结构分析 |

## 5. 逐笔成交和逐笔订单

需要区分两个概念。

### 5.1 逐笔成交

逐笔成交表示每一笔真实成交，通常对应撮合引擎里一次 `maker order` 和 `taker order` 的成交事件。

```text
tradeId
price
qty
side / aggressorSide
timestamp
```

它回答的是：

```text
刚刚成交了什么？
```

其中 `tradeId` 是一笔市场成交的唯一 ID，用于行情、成交查询、K 线聚合、清算和审计。

例如：

```text
tradeId = 1001
symbol = BTC-USDT
price = 60000
qty = 0.4 BTC
makerOrderId = A
takerOrderId = B
```

一张订单可能扫多档，因此可能产生多笔逐笔成交。

### 5.2 逐笔订单

逐笔订单表示订单簿中某一张可见挂单发生了什么变化。

常见变化包括：

```text
Add Order      新增挂单
Modify Order   修改挂单数量或价格
Execute Order  挂单被成交扣减
Delete Order   挂单被撤销或完全移除
```

例如：

```text
Add Order A, BUY, 30000, 1 BTC
Modify Order A, remaining = 0.5 BTC
Execute Order A, executed = 0.3 BTC, remaining = 0.2 BTC
Delete Order A
```

它回答的是：

```text
订单簿里每张订单发生了什么变化？
```

MBO 更接近逐笔订单级行情。

撮合会同时影响这两类数据。

例如订单簿里有：

```text
Ask:
order A  price=60000  qty=1 BTC
```

市价买单成交 `0.4 BTC` 时，行情系统可以同时发布：

```text
逐笔成交:
  tradeId = 1001
  price = 60000
  qty = 0.4 BTC

逐笔订单:
  order = A
  action = EXECUTE
  remainingQty = 0.6 BTC
```

所以：

```text
逐笔成交回答：刚刚成交了什么？
逐笔订单回答：订单簿里的哪张订单发生了什么变化？
```

## 6. 为什么逐笔订单可以重建盘口

订单簿是由一系列状态变更构成的。

如果客户端拿到了完整、有序的订单级增量，就可以在本地维护一份订单簿。

示例：

```text
seq=1 Add    order A BUY  30000 1 BTC
seq=2 Add    order B BUY  30000 2 BTC
seq=3 Add    order C SELL 30010 1 BTC
seq=4 Reduce order A 0.5 BTC
seq=5 Delete order B
```

客户端本地执行这些事件后，可以得到：

```text
Bid:
30000 order A 0.5 BTC

Ask:
30010 order C 1 BTC
```

再聚合价格档，就得到 L2：

```text
Bid:
30000 0.5 BTC

Ask:
30010 1 BTC
```

所以 MBO 可以向下聚合成 MBP / L2，但 L2 通常无法还原完整 MBO。

## 7. 重建盘口至少需要哪些字段

从增量逐笔消息重建订单簿，至少需要：

| 字段 | 作用 |
| --- | --- |
| `sequenceNumber` | 保证增量顺序，检测缺口 |
| `symbol` | 区分交易对 |
| `actionType` | 新增、修改、删除、成交扣减 |
| `orderId` / `orderReference` | 定位具体订单 |
| `side` | 买单或卖单 |
| `price` | 所在价格档 |
| `quantity` / `remainingQty` | 当前数量或变化数量 |
| `timestamp` | 事件时间或发布处理时间 |
| `priority` / `orderPosition` | 可选，用于恢复队列优先级 |

如果只想重建 MBP，可能不需要每个 `orderId`，但至少要有：

```text
symbol
side
price
quantityDelta 或 newTotalQty
sequenceNumber
```

如果要重建 MBO，`orderId` 几乎不可缺。

## 8. MBO 的工程挑战

MBO 价值高，但代价也高。

主要挑战：

- 数据量大
- 网络带宽要求高
- 客户端处理压力大
- 丢一条消息就可能导致订单级状态错误
- 需要更严格的序号和恢复机制
- 对交易所行情发布链路要求更高

因此很多交易所会同时提供：

- L1 ticker
- L2 depth
- trade feed
- full channel / MBO-like feed
- MBO-like 订单级别变化
- full channel 尽可能完整地推送订单簿变化,订单新增、修改、成交扣减等

不同用户根据能力和需求选择不同行情。

## 9. 为什么执行策略偏爱逐笔级别行情

执行策略关心的不只是当前价格，还关心队列和流动性质量。

MBO 可以帮助策略估算：

- 自己挂单前面还有多少数量
- 当前价格档撤单速度
- 大单是否真的存在
- 盘口流动性是否被快速抽走
- 主动买入还是主动卖出更强
- 是否存在频繁挂撤单行为

例如：

```text
L2 显示：
Bid 30000 100 BTC

MBO 显示：
这 100 BTC 由 2 个大订单组成，并且频繁撤单重挂
```

这两种信息对策略的意义完全不同。

## 10. 小练习

根据下面 MBO 增量重建盘口：

```text
seq=1 Add    A BUY  100 5
seq=2 Add    B BUY  100 3
seq=3 Add    C SELL 101 2
seq=4 Add    D SELL 102 4
seq=5 Reduce A 2
seq=6 Delete B
seq=7 Add    E BUY  99 6
```

要求输出：

- MBO 订单簿
- 聚合后的 L2 盘口
- best bid / best ask
- 每个价格档的订单数

## 11. 复盘问题

为什么执行策略通常更偏爱逐笔级别行情？

可以这样回答：

L2 / MBP 只能告诉策略某个价格档有多少聚合数量，但看不到价格档内部订单队列、挂单变化、撤单行为和排队位置。MBO 提供订单级别变化，能帮助策略更准确地估算真实流动性、成交概率、排队时间和短期市场压力。因此对执行算法和高频策略来说，逐笔级别行情通常比聚合盘口更有价值。
