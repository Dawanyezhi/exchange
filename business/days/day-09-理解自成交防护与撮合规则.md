# Day 9：理解自成交防护与撮合规则

## 1. 今天的学习目标

今天的目标是理解自成交防护，也就是 `Self-Trade Prevention` 或 `Self-Match Prevention`。

学完 Day 9 后，需要能回答：

- 什么是自成交
- 为什么交易所要提供自成交防护
- `STP` / `SMP` 和撮合规则有什么关系
- Coinbase 文档中的 `dc`、`co`、`cn`、`cb` 分别是什么意思
- 自成交防护为什么既是业务规则，也是系统设计问题

参考资料：

- Coinbase Exchange Matching Engine：https://docs.cdp.coinbase.com/exchange/concepts/matching-engine
- Coinbase Exchange Trading Concepts：https://docs.cdp.coinbase.com/exchange/concepts/trading

## 2. 什么是自成交

自成交指同一个用户、账户、交易主体或同一组受控账户的买单和卖单互相成交。

示例：

```text
Account A 已有卖单：
SELL 1 BTC @ 30000

Account A 又提交买单：
BUY 1 BTC @ 30000
```

如果不做防护，这两张订单会成交，但真实市场风险没有变化：

```text
Account A 卖出 1 BTC
Account A 买入 1 BTC
```

这会带来几个问题：

- 制造成交量，但没有真实风险转移
- 可能被用于刷量或操纵市场
- 会产生不必要的手续费和账务流水
- 会污染成交价、ticker、K 线和策略信号
- 会让监管、风控和审计更复杂

所以交易所通常会提供自成交防护规则。

## 3. STP / SMP 的含义

`STP` 是 `Self-Trade Prevention`。

`SMP` 是 `Self-Match Prevention`。

两者在很多交易系统里含义接近，都是避免同一交易主体自己的订单互相成交。

工程上关键不是名字，而是三件事：

1. 如何识别“同一主体”
2. 发现自成交时取消哪一边
3. 取消、递减、成交事件如何对外发布

同一主体可能按不同维度判断：

| 维度 | 说明 |
| --- | --- |
| `accountId` | 同一账户自成交 |
| `userId` | 同一用户下多个账户自成交 |
| `profileId` / `portfolioId` | 同一交易组合自成交 |
| `firmId` | 同一机构自成交 |
| `smpGroupId` | 用户配置的一组账户之间禁止互相成交 |

现货交易所常见做法是按账户、profile 或 self-trade prevention id 判断。

## 4. Coinbase 的自成交策略

Coinbase 文档中常见的 STP 策略包括：

| 策略 | 英文含义 | 处理方式 |
| --- | --- | --- |
| `dc` | decrease and cancel | 默认策略。两张订单不成交，数量小的一方取消，数量大的一方扣减相同数量后继续保留；如果数量相同，则两边都取消。 |
| `co` | cancel oldest | 取消较早进入订单簿的订单，保留新订单继续撮合或挂簿。 |
| `cn` | cancel newest | 取消新进入的订单，订单簿上原有订单保留。 |
| `cb` | cancel both | 两张会自成交的订单都取消。 |

这些策略的核心差异是：当 incoming order 与 resting order 属于同一主体且价格可成交时，到底牺牲哪一方的订单状态。

## 5. 策略推演

初始订单簿：

```text
Account A:
SELL 2 BTC @ 30000
```

Account A 新提交：

```text
BUY 1 BTC @ 30000
```

### 5.1 dc：decrease and cancel

`dc` 下不产生成交。

```text
resting sell = 2 BTC
incoming buy = 1 BTC
```

数量小的是 incoming buy，因此：

```text
incoming buy 取消 1 BTC
resting sell 递减 1 BTC
resting sell 剩余 1 BTC
```

订单簿最后：

```text
SELL 1 BTC @ 30000
```

### 5.2 co：cancel oldest

旧订单是订单簿上的 resting sell。

处理结果：

```text
取消 resting sell
incoming buy 保留
```

如果买单还有可成交的其他对手盘，会继续撮合；否则可能挂入买盘。

### 5.3 cn：cancel newest

新订单是 incoming buy。

处理结果：

```text
取消 incoming buy
resting sell 保留
```

订单簿不变。

### 5.4 cb：cancel both

处理结果：

```text
取消 incoming buy
取消 resting sell
```

两边都不再参与撮合。

## 6. 自成交防护和普通撮合的关系

撮合判断不能只看价格。

普通限价买单撮合卖盘时，最基本条件是：

```text
buy.price >= bestAsk.price
```

但生产系统里还要加自成交判断：

```text
if priceCrossed:
    if sameSelfTradeGroup(incomingOrder, restingOrder):
        applyStpRule()
    else:
        match()
```

也就是说，自成交防护发生在“价格可成交之后、真正生成成交之前”。

这一步非常关键，因为一旦生成 fill，下游清算、账本、行情都会把它当作真实成交处理。

## 7. 自成交防护策略表

| 场景 | resting order | incoming order | STP 策略 | 结果 |
| --- | --- | --- | --- | --- |
| 同账户买卖交叉 | A 卖 2 BTC | A 买 1 BTC | `dc` | 不成交，买单取消，卖单递减 1 BTC |
| 同账户买卖交叉 | A 卖 2 BTC | A 买 1 BTC | `co` | 取消旧卖单，买单继续处理 |
| 同账户买卖交叉 | A 卖 2 BTC | A 买 1 BTC | `cn` | 取消新买单，旧卖单保留 |
| 同账户买卖交叉 | A 卖 2 BTC | A 买 1 BTC | `cb` | 新买单和旧卖单都取消 |
| 不同账户买卖交叉 | A 卖 2 BTC | B 买 1 BTC | 任意 | 正常成交 |
| 同用户不同账户 | A1 卖 2 BTC | A2 买 1 BTC | 取决于 STP 分组 | 若同组则 STP，否则正常成交 |

## 8. 工程设计要点

### 8.1 自成交组必须在订单进入撮合前确定

撮合引擎不能在热路径里依赖远程账户查询。

订单进入撮合前，OMS 或风控层应该把以下字段准备好：

```text
accountId
userId / profileId
selfTradePreventionId
selfTradePreventionMode
```

撮合时只做本地判断。

### 8.2 STP 结果也要产生日志和事件

自成交防护不是静默丢弃。

它可能导致：

- incoming order 被取消
- resting order 被取消
- resting order 被递减
- 两边订单都被取消
- 订单状态变化
- 冻结资产释放

因此需要产生可回放事件：

```text
SelfTradePrevented
OrderReduced
OrderCanceled
OrderDone
```

下游 OMS、清算和账户系统必须能理解这些事件。

### 8.3 递减不等于成交

`dc` 里的 decrease 是订单数量减少，但不是成交。

所以不能生成：

```text
trade
fill
fee
ticker update from trade
K line volume
```

否则会把自成交防护错误地记成真实市场成交。

### 8.4 同价位队列位置要谨慎处理

如果 resting order 被递减但未取消，通常仍保留原来的时间优先级。

如果订单被取消后重新提交，则会失去原来的队列位置。

这会影响用户策略，因此撮合规则必须明确、稳定，并且文档化。

## 9. 四组测试样例

### 样例 1：不同账户正常成交

```text
Given:
  Account A: SELL 1 BTC @ 30000

When:
  Account B: BUY 1 BTC @ 30000

Then:
  成交 1 BTC @ 30000
  A 是 maker
  B 是 taker
```

### 样例 2：同账户 `cn`

```text
Given:
  Account A: SELL 1 BTC @ 30000

When:
  Account A: BUY 1 BTC @ 30000, stp=cn

Then:
  incoming BUY 被取消
  resting SELL 保留
  不生成 fill
```

### 样例 3：同账户 `co`

```text
Given:
  Account A: SELL 1 BTC @ 30000

When:
  Account A: BUY 1 BTC @ 30000, stp=co

Then:
  resting SELL 被取消
  incoming BUY 继续处理
  不与自己的 SELL 成交
```

### 样例 4：同账户 `dc`

```text
Given:
  Account A: SELL 3 BTC @ 30000

When:
  Account A: BUY 1 BTC @ 30000, stp=dc

Then:
  BUY 取消
  SELL 剩余 2 BTC
  不生成 fill
```

## 10. 为什么自成交防护是系统设计问题

自成交防护看起来是业务规则，但它会影响整个交易链路：

- 撮合：决定是否生成成交
- OMS：决定订单状态如何变化
- 账户：决定冻结资产如何释放
- 清算：不能对 STP 递减收手续费
- 行情：不能把 STP 当成成交量
- 风控：需要识别自成交主体
- 审计：需要解释为什么订单被取消或递减

如果只在撮合模块里简单 `continue` 跳过，不输出明确事件，下游系统就无法正确更新状态。

## 11. 复盘问题

为什么自成交防护既是业务规则，也是系统设计问题？

可以这样回答：

自成交防护的规则目标是业务和合规层面的：避免同一主体自己的订单互相成交，减少刷量、操纵和无意义成交。但它的落地必须发生在撮合热路径中，并且会改变订单簿、订单状态、资产冻结、成交事件和行情输出。因此它不是一个孤立的参数，而是一套贯穿 OMS、撮合、清算、行情和审计的系统规则。
