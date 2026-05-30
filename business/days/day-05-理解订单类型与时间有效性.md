# Day 5：理解订单类型与时间有效性

## 1. 今天的学习目标

今天的目标是理解订单类型和 Time In Force 如何影响撮合、风控和订单生命周期。

学完 Day 5 后，需要能回答：

- 限价单和市价单的根本区别是什么
- IOC、FOK、GTC、post-only 分别解决什么问题
- 什么订单会挂簿，什么订单只会立即成交或取消
- 为什么订单类型会直接影响撮合和风控设计

参考资料：

- Coinbase Exchange Trading Concepts：https://docs.cdp.coinbase.com/exchange/concepts/trading
- Coinbase Exchange Matching Engine：https://docs.cdp.coinbase.com/exchange/concepts/matching-engine
- Coinbase Advanced Trade Order Types：https://help.coinbase.com/en/coinbase/trading-and-funding/advanced-trade/order-types

## 2. 订单类型的第一性问题

订单类型回答的是：

```text
用户到底想控制什么？
```

限价单控制价格：

```text
我最多用 30000 USDT 买 BTC。
我最低用 31000 USDT 卖 BTC。
```

市价单控制立即性：

```text
我想尽快成交，价格接受当前市场流动性。
```

Time In Force 控制有效期：

```text
这张订单能等多久？
没成交时应该留在订单簿，还是立即取消？
```

Post-only 控制流动性角色：

```text
这张订单只能做 maker，不能立即做 taker。
```

## 3. 常见订单类型

### 3.1 Limit Order

限价单带有明确价格。

买限价单：

```text
成交价格 <= limit price
```

卖限价单：

```text
成交价格 >= limit price
```

限价单可能：

- 立即成交
- 部分成交后剩余挂簿
- 完全不成交并挂簿
- 因 TIF 或 post-only 规则取消

### 3.2 Market Order

市价单目标是立即成交。

它通常没有用户指定成交价格，而是吃对手方订单簿：

- 买市价单吃卖盘
- 卖市价单吃买盘

市价单风险：

- 不保证成交价格
- 大单会扫多档
- 可能产生滑点
- 可能因价格保护、余额不足或流动性不足而部分成交后取消

### 3.3 Post-only Order

Post-only 保证订单只作为 maker。

如果订单会立即成交，则取消或拒绝。

典型用途：

- 做市
- 避免 taker fee
- 避免主动穿透盘口
- 保证订单进入订单簿提供流动性

## 4. Time In Force

### 4.1 GTC

`GTC` 是 Good Till Canceled。

含义：

```text
订单有效直到完全成交或用户主动撤销。
```

特点：

- 可以挂簿
- 可以部分成交后继续保留剩余数量
- 最常见于普通限价单

### 4.2 IOC

`IOC` 是 Immediate Or Cancel。

含义：

```text
立即成交可成交部分，剩余部分取消。
```

特点：

- 不保留未成交部分
- 可以部分成交
- 适合想立即拿流动性，但不想挂簿等待的场景

### 4.3 FOK

`FOK` 是 Fill Or Kill。

含义：

```text
要么全部立即成交，要么全部取消。
```

特点：

- 不允许部分成交
- 通常需要预检查订单簿深度
- 适合必须完整执行的交易意图

### 4.4 Post-only

Post-only 严格来说不是 TIF，而是订单限定条件。

含义：

```text
订单只能进入订单簿做 maker，不能立即成交。
```

如果下单价格会与对手盘交叉：

```text
post-only order -> cancel/reject
```

## 5. 订单类型与 TIF 对照表

| 类型 | 是否带价格 | 是否可能挂簿 | 是否允许部分成交 | 未成交部分如何处理 | 典型用途 |
| --- | --- | --- | --- | --- | --- |
| Limit + GTC | 是 | 是 | 是 | 留在订单簿 | 普通挂单 |
| Limit + IOC | 是 | 否 | 是 | 立即取消 | 立即拿流动性 |
| Limit + FOK | 是 | 否 | 否 | 不足全成则取消 | 必须完整成交 |
| Limit + Post-only | 是 | 是 | 否，入簿前不能成交 | 交叉则取消 | 做 maker |
| Market | 否 | 否 | 是 | 剩余取消 | 快速成交 |

## 6. 小练习：什么订单会挂簿，什么订单只会成交或取消

### 6.1 会挂簿的订单

```text
Limit + GTC:
  如果不能完全立即成交，剩余部分挂簿。

Limit + Post-only:
  如果不会与对手盘交叉，则挂簿。
```

### 6.2 不会挂簿的订单

```text
Market:
  立即吃对手盘，剩余取消。

Limit + IOC:
  立即成交可成交部分，剩余取消。

Limit + FOK:
  能全成才成交，否则取消。
```

### 6.3 例子

当前订单簿：

```text
bestAsk = 101
bestBid = 99
```

买限价 GTC，价格 100：

```text
不成交，挂买盘 100。
```

买限价 GTC，价格 101：

```text
立即吃卖盘 101，剩余可能继续挂 101。
```

买限价 IOC，价格 101：

```text
立即吃卖盘 101，剩余取消。
```

买限价 FOK，价格 101，数量大于 101 档可用数量：

```text
如果订单簿在 101 以内不够全成，则整单取消。
```

买 post-only，价格 101：

```text
会和卖一 101 交叉，因此取消或拒绝。
```

买市价单：

```text
从卖一开始逐档吃单，剩余取消。
```

## 7. 订单类型如何影响风控

不同订单类型需要不同风控。

### 7.1 限价买单

冻结 quote：

```text
lockedQuote = price * quantity + feeReserve
```

因为最坏情况下，买单会按限价成交。

### 7.2 市价按金额买单

冻结 quote：

```text
lockedQuote = targetQuoteAmount + feeReserve
```

撮合时按每档价格计算能买多少 base。

### 7.3 市价按数量买单

需要按保护价或滑点上限冻结 quote：

```text
lockedQuote = targetBaseQty * maxAcceptablePrice + feeReserve
```

不能只用当前 best ask，因为市价单可能扫多档。

### 7.4 卖单

通常冻结 base：

```text
lockedBase = sellBaseQty
```

如果是按 quote 金额卖出，需要按保护价下限估算最大要卖多少 base：

```text
lockedBase = targetQuoteAmount / minAcceptablePrice
```

## 8. 订单类型如何影响撮合

撮合引擎需要根据订单类型选择不同路径。

限价 GTC：

```text
先撮合可成交部分，剩余挂簿。
```

限价 IOC：

```text
先撮合可成交部分，剩余取消。
```

限价 FOK：

```text
先预检查能否全成，能全成才真正撮合。
```

Post-only：

```text
先检查是否与对手盘交叉，交叉则取消，不交叉才挂簿。
```

市价单：

```text
按保护价逐档吃对手盘，剩余取消。
```

## 9. 复盘问题：订单类型为什么会直接影响撮合和风控设计

订单类型影响撮合，因为它决定：

- 是否允许立即成交
- 是否允许挂簿
- 是否允许部分成交
- 是否需要预检查订单簿深度
- 剩余部分应挂簿还是取消
- 成交价格如何受限

订单类型影响风控，因为它决定：

- 需要冻结 base 还是 quote
- 冻结金额如何计算
- 是否需要价格保护
- 是否可能产生滑点
- 是否需要处理未使用预算释放
- 是否需要防止余额不足导致成交后无法清算

因此订单类型不是简单枚举，而是交易系统行为分支的入口。

## 10. 和当前项目的关系

当前项目中已有：

```text
OrderType:
  LIMIT
  MARKET

TimeInForceEnum:
  GTC
  IOC
  FOK
  POST_ONLY
```

撮合引擎中对应逻辑包括：

- `processLimitOrder`
- `processFokOrder`
- `processMarketOrder`
- post-only 交叉检查
- IOC 剩余取消
- FOK 预撮合
- 市价单保护价和预算控制

Day 5 的理解可以直接映射到代码：

```text
OrderType + TimeInForceEnum
  -> 决定 MatchEngine 选择哪条处理路径
```

## 11. 今日检查清单

- 能解释 limit 和 market 的区别。
- 能解释 GTC、IOC、FOK、post-only。
- 能判断一张订单是否会挂簿。
- 能说明市价单为什么需要价格保护。
- 能说明 FOK 为什么需要预检查订单簿深度。
- 能说明订单类型如何影响冻结资金。

## 12. 今日结论

订单类型和时间有效性是交易系统行为的核心控制参数。

它们不仅影响撮合路径，也影响风控冻结、订单状态机、用户回报、手续费和账务释放。生产系统里，订单类型不是 UI 上的一个选项，而是整个交易链路的一组强约束。
