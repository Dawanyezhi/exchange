# Day 10：理解订单接入协议

## 1. 今天的学习目标

今天的目标是理解订单接入协议为什么要设计得稳定、明确、低歧义。

学完 Day 10 后，需要能回答：

- 订单接入协议解决什么问题
- Nasdaq OUCH 这类协议为什么强调字段、消息类型和编码约束
- 一个下单报文通常包含哪些字段
- 如何把字段分成身份、标的、价格、数量、控制、会话六类
- 为什么高性能系统特别强调协议字段稳定性

参考资料：

- Nasdaq OUCH：https://www.nasdaqtrader.com/Trader.aspx?id=OUCH
- Coinbase Exchange FIX API：https://docs.cdp.coinbase.com/exchange/fix-api

## 2. 订单接入协议是什么

订单接入协议是客户端和交易所之间传递交易指令的接口规范。

它承载的不是行情，而是用户的交易动作：

- 新建订单
- 撤销订单
- 修改订单
- 查询订单状态
- 接收订单确认
- 接收成交回报
- 接收拒单原因

简化链路：

```text
Client
  -> Order Entry Protocol
  -> Gateway / Session
  -> OMS
  -> Risk
  -> Matching
```

订单协议的核心要求是：交易所和客户端对同一段字节、同一个字段、同一个状态有完全一致的解释。

## 3. 为什么参考 OUCH

Nasdaq OUCH 是典型的低延迟订单接入协议。

它的价值不在于让每个交易所照抄字段，而在于体现了一类订单协议的设计思想：

- 消息类型明确
- 字段长度固定或强约束
- 编码规则稳定
- 尽量减少歧义和动态解析成本
- 每个请求和回报都能对应到确定状态变化
- 适合高吞吐、低延迟、可回放的交易链路

这类协议追求的是确定性，而不是表达灵活性。

## 4. 常见消息类型

订单接入协议通常至少包含这些消息：

| 消息类型 | 方向 | 说明 |
| --- | --- | --- |
| `Enter Order` | Client -> Exchange | 新建订单 |
| `Cancel Order` | Client -> Exchange | 撤销订单 |
| `Replace Order` | Client -> Exchange | 修改订单，通常用于改价格或数量 |
| `Order Accepted` | Exchange -> Client | 订单被接收 |
| `Order Rejected` | Exchange -> Client | 订单被拒绝 |
| `Order Canceled` | Exchange -> Client | 订单被撤销 |
| `Order Executed` | Exchange -> Client | 订单产生成交 |
| `Order Replaced` | Exchange -> Client | 订单修改成功 |
| `Broken Trade` | Exchange -> Client | 成交被交易所撤销或冲正 |

不同交易所命名不同，但核心语义接近。

## 5. 订单接入协议字段分类表

| 分类 | 常见字段 | 解决的问题 |
| --- | --- | --- |
| 身份字段 | `accountId`、`userId`、`clientOrderId`、`orderId`、`senderCompId` | 谁发起的订单，订单如何幂等识别 |
| 标的字段 | `symbol`、`productId`、`baseCurrency`、`quoteCurrency` | 交易哪个市场 |
| 价格字段 | `price`、`stopPrice`、`limitPrice`、`quoteAmount` | 订单价格或金额约束 |
| 数量字段 | `quantity`、`baseQty`、`displayQty`、`remainingQty` | 交易多少基础币或展示多少数量 |
| 控制字段 | `side`、`orderType`、`timeInForce`、`postOnly`、`stpMode` | 订单如何执行 |
| 会话字段 | `sequenceNumber`、`sendingTime`、`sessionId`、`messageType` | 消息顺序、重放、会话管理 |

这六类字段合在一起，才构成一条完整订单指令。

## 6. 字段解释

### 6.1 身份字段

身份字段用于解决“这是谁的订单”和“这条请求是否重复”。

常见字段：

```text
accountId
userId
clientOrderId
orderId
```

`clientOrderId` 通常由客户端生成，用于幂等：

```text
客户端因为网络超时重复发送同一个下单请求
交易所可以根据 clientOrderId 判断是否重复
```

`orderId` 通常由交易所生成，用于内部状态流转和外部查询。

### 6.2 标的字段

标的字段用于说明交易市场。

例如：

```text
symbol = BTC-USDT
baseCurrency = BTC
quoteCurrency = USDT
```

生产系统里不能只传 `BTC`，因为：

- `BTC-USDT` 和 `BTC-USD` 是不同市场
- 现货、永续、交割合约是不同产品
- 不同 symbol 有不同精度、最小数量和风控规则

### 6.3 价格字段

价格字段用于表达订单的价格约束。

限价单常见字段：

```text
price = 30000
```

按 quote 金额的市价买单可能使用：

```text
quoteAmount = 1000 USDT
```

价格字段必须使用定点整数或高精度十进制，不能在协议层使用二进制浮点表达交易金额。

### 6.4 数量字段

数量字段用于表达交易规模。

常见形式：

```text
baseQty = 0.5 BTC
quoteAmount = 1000 USDT
displayQty = 0.1 BTC
```

需要注意：

- `baseQty` 表示基础币数量
- `quoteAmount` 表示计价币金额
- 市价买单可能按 base 数量或 quote 金额表达
- 卖单通常消耗 base，但也可能支持按目标 quote 金额卖出

协议必须明确订单目标单位，否则撮合和风控会产生歧义。

### 6.5 控制字段

控制字段决定订单如何执行。

常见字段：

```text
side = BUY / SELL
orderType = LIMIT / MARKET
timeInForce = GTC / IOC / FOK
postOnly = true / false
stpMode = dc / co / cn / cb
```

这些字段会直接影响：

- 是否允许挂簿
- 是否必须立即成交
- 是否允许部分成交
- 是否执行自成交防护
- 是否参与 maker/taker 费率计算

### 6.6 会话字段

会话字段用于解决消息传输可靠性。

常见字段：

```text
sequenceNumber
sendingTime
sessionId
messageType
```

这些字段本身不表达交易意图，但它们保证交易指令能被有序、可恢复地处理。

## 7. 编码约束

订单接入协议常见编码方式：

| 编码方式 | 优点 | 缺点 |
| --- | --- | --- |
| FIX tag=value | 标准化、可读性较好 | 文本解析成本较高 |
| JSON REST | 易接入、适合普通 API | 延迟高、字段歧义风险更大 |
| Binary protocol | 低延迟、字段固定、解析快 | 接入门槛高、兼容要求严格 |
| SBE | 低延迟、结构化、适合演进 | schema 管理和版本兼容要求高 |

高性能订单接入协议通常会避免：

- 任意精度浮点
- 无约束字符串
- 动态嵌套结构
- 同一个字段在不同场景下表达不同含义
- 依赖字段顺序之外的隐含规则

## 8. 确定性为什么重要

交易系统里，协议不是单纯的数据传输格式，而是状态机输入。

如果协议含义不稳定，会导致：

- 客户端和交易所解释不一致
- 订单状态无法重放
- 新老版本兼容困难
- 风控和撮合看到的语义不同
- 成交纠纷难以审计

例如：

```text
quantity = 100
```

如果协议没有说明它是：

- 100 个 base
- 100 个 quote
- 100 张合约
- 100 个最小交易单位

撮合结果就无法被正确解释。

## 9. 高性能系统为什么强调字段稳定性

字段稳定性意味着：

- 字段含义不能随意变化
- 字段类型不能随意变化
- 枚举值不能随意复用
- 新字段要能被旧客户端忽略或安全拒绝
- 老字段废弃要保留兼容窗口

原因是交易协议一旦上线，会被多个系统依赖：

```text
API Gateway
Session
OMS
Risk
Matching
Clearing
Market Data
Client SDK
Quant System
Replay Tool
Audit Tool
```

字段变化不是改一行代码，而是一次协议升级。

## 10. 小练习

把下面订单请求字段分类：

```text
messageType = NewOrder
sequenceNumber = 1024
accountId = 10001
clientOrderId = abc-001
symbol = BTC-USDT
side = BUY
orderType = LIMIT
price = 30000
baseQty = 1
timeInForce = GTC
postOnly = false
stpMode = dc
sendingTime = 2026-05-30T10:00:00Z
```

要求分成：

- 身份
- 标的
- 价格
- 数量
- 控制
- 会话

## 11. 复盘问题

为什么高性能系统特别强调协议字段的稳定性？

可以这样回答：

订单接入协议是交易状态机的输入格式。字段一旦变化，影响的不只是网关解析，还会影响 OMS 建单、风控校验、撮合语义、成交回报、清算入账、日志回放和外部客户系统。高性能交易系统通常依赖固定字段、固定类型和固定枚举来降低解析成本，并保证状态可重放、可审计、可兼容升级。
