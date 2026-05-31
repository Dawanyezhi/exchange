# Day 12：理解行情协议

## 1. 今天的学习目标

今天的目标是理解行情协议和订单接入协议的本质区别。

学完 Day 12 后，需要能回答：

- 行情协议解决什么问题
- 行情消息和下单消息有什么不同
- ITCH 和 OUCH 在设计目标上有什么差异
- 为什么订单接入和行情分发通常要拆开
- 行情协议为什么特别重视序号、快照、增量和恢复

参考资料：

- Nasdaq TotalView-ITCH 5.0 Specification：https://nasdaqtrader.com/content/technicalsupport/specifications/dataproducts/NQTVITCHSpecification.pdf
- Nasdaq OUCH：https://www.nasdaqtrader.com/Trader.aspx?id=OUCH
- Coinbase Exchange FIX Market Data：https://docs.cdp.coinbase.com/exchange/fix-api/market-data
- Coinbase Exchange WebSocket Feed：https://docs.cdp.coinbase.com/exchange/websocket-feed/overview

## 2. 行情协议是什么

行情协议用于向客户端分发市场状态变化。

它通常包括：

- 最新成交
- 订单簿快照
- 订单簿增量
- 最优买卖价
- ticker
- K 线
- 交易状态
- 产品状态

简化链路：

```text
Matching Engine
  -> Match / Book Event
  -> Market Data Engine
  -> Market Data Protocol
  -> Client / Strategy
```

行情协议的核心目标是让外部系统尽可能准确地重建市场状态。

## 3. 行情消息和下单消息的本质区别

订单接入协议是“请求型”的。

客户端向交易所发送指令：

```text
我要买
我要卖
我要撤单
```

行情协议是“发布型”的。

交易所向所有订阅者广播市场变化：

```text
发生了一笔成交
买一数量变了
卖一价格变了
订单簿新增一档
```

核心差异：

| 维度 | 订单接入协议 | 行情协议 |
| --- | --- | --- |
| 方向 | 客户端到交易所为主 | 交易所到客户端为主 |
| 消息性质 | 私有请求和私有回报 | 公共市场数据 |
| 核心目标 | 正确处理交易指令 | 正确传播市场状态 |
| 关注重点 | 幂等、风控、订单状态 | 序号、快照、增量、缺包恢复 |
| 权限范围 | 只看自己的订单和成交 | 看市场公开数据 |
| 状态依赖 | OMS / Account / Matching | Matching / Market Data |

## 4. OUCH 与 ITCH 的差异

OUCH 是订单接入协议。

ITCH 是行情分发协议。

| 对比项 | OUCH | ITCH |
| --- | --- | --- |
| 主要用途 | 下单、撤单、改单、执行回报 | 发布订单簿、成交和市场状态 |
| 消息方向 | 客户端与交易所双向 | 交易所向市场参与者广播 |
| 数据范围 | 某个客户自己的订单 | 整个市场公开行情 |
| 关键字段 | order token、side、price、shares | order reference、price、shares、stock、event type |
| 性能目标 | 低延迟接收交易指令 | 高吞吐分发市场变化 |
| 恢复重点 | 会话序号和订单回报补齐 | 行情序号、快照和增量恢复 |

order reference 订单引用号，行情协议里用于标识某一张订单的 ID

price 价格

shares 数量

stock 交易标的

event type 行情事件类型，例如新增订单、订单成交、订单取消、交易开始、交易暂停、市场关闭等

一句话：

```text
OUCH 用来告诉交易所“我要做什么”
ITCH 用来告诉市场“刚刚发生了什么”
```

## 5. 行情消息类型

行情协议常见消息包括：

| 类型 | 说明 |
| --- | --- |
| `Trade` | 最新成交，包含价格、数量、时间、成交 ID |
| `Book Snapshot` | 某一时刻完整或部分订单簿 |
| `Book Incremental` | 订单簿变更，例如新增、修改、删除 |
| `Ticker` | 某交易对的聚合摘要，例如最新价、24h 高低、成交量 |
| `Best Bid Offer` | 最优买价和最优卖价 |
| `Instrument Status` | 产品状态，例如 trading、halted、auction |
| `Heartbeat` | 行情连接保活或空消息 |
| `Sequence Reset` | 序号重置或恢复边界 |

不同交易所会有不同命名，但核心信息类似。

## 6. 行情协议为什么需要序号

行情客户端要用增量消息维护本地订单簿。

如果丢一条消息，本地订单簿就可能永久错误。

示例：

```text
seq=100: Ask 30000 add 1 BTC
seq=101: Ask 30000 reduce 0.5 BTC
seq=102: Ask 30000 delete
```

如果客户端丢了 `seq=101`，它可能看到：

```text
seq=100: add 1 BTC
seq=102: delete
```

这个例子最终可能还能删掉，但复杂场景下会导致：

- 数量错误
- 价格档位错误
- 订单队列错误
- 最优价错误
- 策略基于错误行情下单

所以行情协议必须提供连续序号，让客户端发现缺口。行情协议的连续序号最好按 symbol/channel 生成

## 7. 行情和撮合的关系

撮合引擎产生原始事件：

```text
OrderAccepted 订单已被系统接受
OrderMatched
OrderCanceled
BookUpdated 订单簿发生变化
```

行情引擎消费这些事件后，生成适合外部分发的行情：

```text
Trade Feed  逐笔成交流
Depth Feed  深度行情 / 盘口流 
Ticker      市场摘要
K Line
Index / Mark Price  
    Index Price 指数价格。通常由多个外部现货市场价格加权计算
    Mark Price 标记价格。合约系统用来计算未实现盈亏和强平风险，通常基于index price、资金费率、合理基差、盘口保护
```

生产系统里通常不建议让撮合直接负责所有行情格式。

更常见的结构是：

```text
Matching Engine
  -> Sequenced Event Log
  -> Market Data Builder
  -> Market Data Publisher
  -> Client
```

这样做的好处：

- 撮合热路径更短
- 行情协议可以独立演进
- 多种行情产品可以复用撮合事件
- 行情可以回放重建
- 缺包恢复和快照生成可以独立处理

## 8. Order Entry vs Market Data 协议对比表

| 维度 | Order Entry | Market Data |
| --- | --- | --- |
| 典型协议 | OUCH、FIX Order Entry、REST Trading API | ITCH、FIX Market Data、WebSocket Feed |
| 参与者 | 单个客户和交易所 | 所有订阅行情的客户 |
| 是否私有 | 私有 | 公开或按权限分发 |
| 是否改变交易所状态 | 会改变订单和资产状态 | 不应改变交易所交易状态 |
| 消息模型 | 请求 / 响应 / 执行回报 | 发布 / 订阅 |
| 关键问题 | 幂等、风控、状态机 | 顺序、完整性、快照增量 |
| 丢消息影响 | 客户订单状态不一致 | 本地市场视图不一致 |
| 恢复方式 | resend、订单查询、成交查询 | snapshot、incremental replay、sequence check |

## 9. 为什么订单接入和行情分发要拆开

主要原因有五个。

第一，访问权限不同。

订单接入是私有通道，行情是公共或半公共数据。

第二，流量模型不同。

下单是少数客户向交易所发送请求，行情是交易所向大量客户广播。

第三，性能瓶颈不同。

订单接入关注低延迟、风控和确定性，行情关注高吞吐、多订阅者和网络分发。

第四，状态语义不同。

订单接入改变交易状态，行情只传播交易状态。

第五，恢复机制不同。

订单接入依赖会话回报和查询接口，行情依赖快照、增量和序号。

## 10. 小练习

列出 ITCH 和 OUCH 在设计目标上的差异。

可以按下面表格填写：

| 问题 | OUCH | ITCH |
| --- | --- | --- |
| 谁发送消息 |  |  |
| 谁接收消息 |  |  |
| 是否用于下单 |  |  |
| 是否用于重建订单簿 |  |  |
| 最怕丢什么消息 |  |  |
| 如何恢复 |  |  |

## 11. 复盘问题

为什么很多系统会把订单接入和行情分发彻底拆开？

可以这样回答：

订单接入和行情分发面对的是两类完全不同的问题。订单接入负责接收私有交易指令并改变系统状态，重点是鉴权、风控、幂等和订单状态机；行情分发负责把公开市场状态广播给大量订阅者，重点是顺序、完整性、快照增量和高吞吐。把两者拆开，可以减少撮合热路径负担，让交易处理和行情服务分别按自己的性能模型和恢复机制演进。
