# Day 15：形成撮合与行情模块总结

## 1. 今天的学习目标

今天的目标是把 Day 8 到 Day 14 的内容串成完整闭环。

学完 Day 15 后，需要能回答：

- 订单消息如何进入撮合
- 撮合如何维护订单簿
- 成交事件如何变成行情消息
- 订单接入协议和行情协议如何分工
- 快照、增量、序号在行情恢复中分别起什么作用
- 撮合引擎和行情引擎之间共享哪些核心概念

参考资料：

- Coinbase Exchange Matching Engine：https://docs.cdp.coinbase.com/exchange/concepts/matching-engine
- Coinbase Exchange Trading Concepts：https://docs.cdp.coinbase.com/exchange/concepts/trading
- Coinbase Exchange FIX Market Data：https://docs.cdp.coinbase.com/exchange/fix-api/market-data
- Nasdaq OUCH：https://www.nasdaqtrader.com/Trader.aspx?id=OUCH
- Nasdaq TotalView-ITCH 5.0 Specification：https://nasdaqtrader.com/content/technicalsupport/specifications/dataproducts/NQTVITCHSpecification.pdf
- CME Market by Order (MBO) FAQ：https://www.cmegroup.com/articles/faqs/market-by-order-mbo.html

## 2. Phase 2 总结

Phase 2 的主线是：协议把订单和行情变成可传输、可排序、可恢复的消息；撮合引擎把订单消息变成订单簿状态和成交事件；行情系统再把撮合产生的状态变化变成外部可订阅的市场数据。

订单进入交易所后，不会直接“买卖成功”。它首先要通过订单接入协议进入网关和会话层。订单协议负责清楚表达交易意图，例如交易哪个 symbol、买还是卖、限价还是市价、数量是多少、是否允许挂簿、time in force 是什么、自成交防护模式是什么。会话层则负责登录、心跳、序号、重发和断线恢复，保证这些交易指令在应用层是有序、可恢复、可审计的。

订单进入撮合前，系统已经应该把关键业务语义解析清楚。撮合引擎的核心工作是维护订单簿。订单簿由买盘和卖盘组成，买盘高价优先，卖盘低价优先，同一价格档内时间优先。新订单到达时，撮合引擎先检查是否能与对手盘成交；如果价格穿透，就按价格时间优先逐笔撮合，生成 fill；如果不能成交或只成交了一部分，剩余数量在规则允许时进入订单簿。

maker 和 taker 是撮合结果中的重要身份。挂在订单簿上提供流动性的订单是 maker，主动打掉对手盘流动性的订单是 taker。限价单既可能是 maker，也可能是 taker；市价单通常是 taker。成交价通常来自订单簿上已有的 resting order，因此限价买单可能以低于自己限价的价格成交，这就是 price improvement。

自成交防护是撮合规则中容易被低估的一部分。当 incoming order 和 resting order 属于同一账户、同一用户或同一 self-trade group 时，即使价格可成交，也不能直接生成 fill。系统需要根据 `dc`、`co`、`cn`、`cb` 等策略取消、递减或保留订单。关键点是：自成交防护不是成交，不能产生手续费、成交量和 ticker 成交价，但它会改变订单状态和订单簿状态，所以必须输出明确事件供 OMS、账户和审计系统处理。

撮合引擎产生的成交和订单簿变化，会进入行情系统。行情协议与订单接入协议不同：订单接入是私有请求，行情分发是公共或半公共广播；订单接入改变交易所状态，行情协议传播交易所状态。OUCH 这类协议偏订单接入，ITCH 这类协议偏行情分发。生产系统通常把两者拆开，避免撮合热路径承担大量行情格式化和网络广播工作。

行情可以分为 L1、L2、MBP 和 MBO。L1 只提供最优买卖价和最新成交；L2/MBP 按价格档聚合数量；MBO 提供订单级别变化。MBO 信息量最大，可以向下聚合成 L2，但 L2 通常无法反推出订单级队列。执行策略更偏爱 MBO 或逐笔级别行情，因为它能帮助估算排队位置、真实流动性、撤单行为和短期市场压力。

行情系统最重要的工程模型是快照 + 增量。快照提供某个序号点上的订单簿完整状态，增量提供该序号之后的连续变化。客户端通常先订阅增量并缓存，再拉取快照，然后丢弃快照序号之前的增量，从 `snapshotSeq + 1` 开始按顺序应用。每条增量都必须检查 sequence number；一旦发现缺口，不能继续相信本地订单簿，必须补齐缺失增量或重新同步。

这一阶段最重要的结论是：撮合和行情不是两个孤立模块，而是围绕同一组状态和事件工作的上下游。撮合关注订单簿的真实状态变更和成交确定性，行情关注把这些状态变更完整、有序地发布出去。订单簿、成交、序号、快照、增量、价格档、订单 ID、成交 ID 是两者共享的核心概念。一个成熟交易系统必须让撮合结果可以被清算消费、被 OMS 回报、被行情重建、被日志回放、被审计解释。

## 3. 订单消息如何变成行情消息

完整链路如下：

```text
NewOrder
  -> Gateway 鉴权和基础校验
  -> Session 分配或检查序号
  -> OMS 建立订单状态
  -> Risk 前置风控和资产冻结
  -> Matching 按订单簿撮合
  -> Match Event / Book Event
  -> Market Data Builder
  -> Trade Feed / Depth Feed / Ticker
  -> Client
```

关键点：

- 订单消息是交易意图
- 撮合事件是交易所内部确定状态
- 行情消息是面向外部发布的市场状态

这三者不能混为一谈。

## 4. 撮合与行情闭环图

```mermaid
flowchart LR
    ClientOrder[Client Order] --> OrderProtocol[Order Entry Protocol]
    OrderProtocol --> Session[Session Layer]
    Session --> OMS[OMS]
    OMS --> Risk[Pre-trade Risk]
    Risk --> Matching[Matching Engine]
    Matching --> Book[Order Book]
    Matching --> MatchEvent[Match / Book Event Log]
    MatchEvent --> Clearing[Clearing]
    MatchEvent --> MarketData[Market Data Engine]
    MarketData --> TradeFeed[Trade Feed]
    MarketData --> DepthFeed[Depth Feed]
    MarketData --> Ticker[Ticker]
    TradeFeed --> MarketClient[Market Data Client]
    DepthFeed --> MarketClient
    Ticker --> MarketClient
```
![img.png](assets/撮合与行情闭环图.png)

## 5. 撮合引擎和行情引擎共享的核心概念

| 概念 | 撮合中的含义 | 行情中的含义 |
| --- | --- | --- |
| `symbol` | 订单簿分片和撮合市场 | 行情订阅主题 |
| `orderId` | 订单状态和撤单定位 | MBO 重建订单簿 |
| `price` | 撮合比较和价格档 | 深度档位和成交价格 |
| `quantity` | 可成交数量和剩余数量 | 档位数量、成交数量 |
| `side` | 买盘或卖盘 | bid / ask |
| `sequence` | 撮合命令和事件顺序 | 行情增量顺序 |
| `tradeId` | 成交唯一标识 | trade feed、ticker、K 线来源 |
| `snapshot` | 订单簿状态快照 | 客户端初始化本地簿 |
| `incremental` | 状态变更事件 | 客户端实时更新本地簿 |

## 6. 最小生产实践原则

### 6.1 撮合侧

撮合模块至少要保证：

- 同一 symbol 内命令严格有序
- 订单簿状态只由撮合线程或确定性事件修改
- 价格和数量使用整数或高精度定点数
- 价格时间优先规则稳定
- 自成交防护在生成 fill 前执行
- 每笔 fill 有全局或 symbol 内唯一 ID
- 每个订单状态变化都有事件
- 事件可以回放恢复订单簿

### 6.2 行情侧

行情模块至少要保证：

- 从撮合事件按顺序构建行情
- trade、depth、ticker 的来源一致
- 每条增量带 sequence number
- 快照带 snapshot sequence
- 客户端能检测缺口
- 缺口后有重放或重新同步机制
- 行情发布不反向阻塞撮合热路径

### 6.3 协议侧

协议设计至少要保证：

- 字段含义稳定
- 枚举值不可随意复用
- 数量单位明确区分 base 和 quote
- 会话层有登录、心跳、序号和重发
- 新旧版本兼容策略清晰
- 订单回报和行情事件都有可审计 ID

## 7. 小练习

用自己的语言讲清楚“订单消息如何变成行情消息”。

建议按下面结构输出：

```text
1. 客户端如何表达交易意图
2. 会话层如何保证消息顺序和恢复
3. OMS 和风控在撮合前做什么
4. 撮合如何更新订单簿并生成 fill
5. 成交事件如何进入行情系统
6. 行情系统如何生成 trade、depth、ticker
7. 客户端如何用 snapshot + incremental 维护本地订单簿
```

## 8. 复盘问题

撮合引擎和行情引擎之间共享了哪些核心概念？

可以这样回答：

撮合引擎和行情引擎共享订单簿、价格档、买卖方向、订单 ID、成交 ID、成交价格、成交数量、事件序号、快照和增量等概念。区别在于撮合引擎维护真实交易状态并决定成交，行情引擎消费这些状态变化并向外部发布可订阅、可恢复的市场视图。两者之间最好通过有序事件日志连接，而不是让行情直接读取或修改撮合内部状态。
