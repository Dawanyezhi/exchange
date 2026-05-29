# 交易系统业务/技术文章清单

## 这份清单怎么用

这是一份按模块拆分的资料索引，目标不是“把文章堆起来”，而是帮你快速找到：

- 先看什么，建立全景
- 哪些资料适合理解业务链路
- 哪些资料适合理解工程实现
- 哪些资料值得在设计系统原型时反复翻

建议优先级：

1. 先读 `官方文档`
2. 再读 `技术博客`
3. 再看 `公众号镜像/社区文章`
4. 最后结合 `开源项目` 看实现

说明：

- 本清单有意识避开了 `国内期货业务规则 / CTP / 开平今昨 / 夜盘细则`
- 部分腾讯云文章是公众号镜像或社区转载，适合帮助建立直觉，但权威性通常不如官方文档
- 有些 `账务 / 对账 / 状态机` 文章来自支付、电商或订单系统领域，不是撮合交易专用资料，但对理解交易系统中的订单、台账、清结算仍然很有帮助

## 一、全景图与系统分层

### 官方文档

- Coinbase Concepts Overview
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/overview
  - 适合看什么：用来先建立“交易系统里到底有哪些核心对象和概念”

- Coinbase Trading
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/trading
  - 适合看什么：理解订单类型、时间有效性、自成交防护、产品对这些基础概念

- Coinbase Account Structure
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/structure
  - 适合看什么：理解账户、Profile、Account、Ledger 之间的层次关系

### 公众号镜像 / 社区文章

- 算法交易系统架构，此篇足矣！
  - 类型：公众号镜像
  - 链接：https://cloud.tencent.com/developer/article/2337506
  - 适合看什么：快速建立交易系统大图，适合第一遍扫盲

- 交易系统架构演进之路（一）：1.0 版
  - 类型：公众号镜像
  - 链接：https://cloud.tencent.com/developer/article/1144017
  - 适合看什么：看一个交易系统从简单版本往外长时，模块是怎么出现的

- 交易系统架构演进之路（二）：2.0 版
  - 类型：公众号镜像
  - 链接：https://cloud.tencent.com/developer/article/1148285
  - 适合看什么：理解订单、清算、资产、行情开始分层后的架构形态

- 交易系统架构演进之路（三）：微服务化
  - 类型：公众号镜像
  - 链接：https://cloud.tencent.com/developer/article/1766276
  - 适合看什么：理解为什么很多交易系统最后会拆成交易、撮合、风控、行情、清算等服务

## 二、订单生命周期、订单状态机与 OMS

### 官方文档

- Coinbase Trading
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/trading
  - 适合看什么：订单类型、TIF、post-only、STP 这些会直接进入订单状态机设计

- Coinbase Matching Engine
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/matching-engine
  - 适合看什么：看订单进入系统后的撮合与状态变化

### 公众号镜像 / 社区文章

- 解构国际汇款产品之交易系统
  - 类型：产品/交易系统文章
  - 链接：https://cloud.tencent.com/developer/news/420009
  - 适合看什么：虽然不是撮合市场，但对理解交易服务、订单管理、状态机很有帮助

- 高德打车通用可编排订单状态机引擎设计
  - 类型：状态机工程文章
  - 链接：https://cloud.tencent.com/developer/article/2090541
  - 适合看什么：理解复杂订单状态如何做成可编排状态机

- 关于处理电商系统订单状态的流转，分享下我的技术方案
  - 类型：状态机工程文章
  - 链接：https://cloud.tencent.com/developer/article/1849761
  - 适合看什么：看订单状态流转如何抽象成统一入口和事件驱动

- 管理订单状态，该用上状态机吗？
  - 类型：状态机工程文章
  - 链接：https://cloud.tencent.com/developer/article/2088154
  - 适合看什么：帮助你判断状态机适合解决什么问题，不适合解决什么问题

## 三、撮合引擎、订单簿与成交规则

### 官方文档

- Coinbase Matching Engine
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/matching-engine
  - 适合看什么：价格时间优先、maker/taker、部分成交、自成交防护

- CME Market by Order (MBO) FAQ
  - 类型：官方文档
  - 链接：https://www.cmegroup.com/articles/faqs/market-by-order-mbo.html
  - 适合看什么：理解逐笔级别订单数据和盘口级别数据的差异

### 技术博客 / 社区文章

- Devexperts: Matching Engine for Crypto and Stock Exchanges
  - 类型：技术博客
  - 链接：https://devexperts.com/matching-engine/
  - 适合看什么：偏工程视角理解撮合核心能力、延迟、吞吐和扩展性

- 撮合系统是什么？把“买卖双方配对成成交”的那台发动机到底做了什么
  - 类型：公众号镜像
  - 链接：https://cloud.tencent.com/developer/article/2595308
  - 适合看什么：适合第一次把撮合系统的职责讲清楚

- 交易所撮合引擎原理及实现代码
  - 类型：教程型文章
  - 链接：https://cloud.tencent.com/developer/article/1470996
  - 适合看什么：用较直白的语言理解订单簿和撮合主循环

- 交易所撮合交易〖一〗
  - 类型：工程实践文章
  - 链接：https://cloud.tencent.com/developer/article/1546278
  - 适合看什么：理解“同一交易对为什么很难并行撮合”这个核心限制

- 撮合引擎开发：解密黑箱流程
  - 类型：工程实践文章
  - 链接：https://cloud.tencent.com/developer/article/1547202
  - 适合看什么：把开启撮合、单线程处理、顺序性这些问题讲得比较直观

- 撮合交易系统服务边界与设计
  - 类型：工程实践文章
  - 链接：https://cloud.tencent.com/developer/article/1644833
  - 适合看什么：看撮合、账户、清算、行情等模块的职责边界

## 四、订单接入协议、会话层与回报链路

### 官方文档

- Nasdaq OUCH
  - 类型：官方文档
  - 链接：https://www.nasdaqtrader.com/Trader.aspx?id=OUCH
  - 适合看什么：理解高性能订单接入协议长什么样

- Coinbase Exchange FIX API Connectivity
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/fix-api/connectivity
  - 适合看什么：理解 FIX 会话、连接、TLS、端点、会话边界

- Coinbase Exchange FIX Best Practices
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/fix-api/best-practices
  - 适合看什么：看真实生产环境里如何做连接、batch、modify、drop copy

### 延伸阅读

- Coinbase Exchange FIX API 首页
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/fix-api
  - 适合看什么：顺着目录继续看 order entry、market data、dictionary 下载

## 五、行情系统、快照增量与本地订单簿重建

### 官方文档

- Nasdaq TotalView-ITCH 5.0 Specification
  - 类型：官方文档
  - 链接：https://nasdaqtrader.com/content/technicalsupport/specifications/dataproducts/NQTVITCHSpecification.pdf
  - 适合看什么：理解市场数据协议的消息定义和序号语义

- Coinbase Exchange FIX Market Data
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/fix-api/market-data
  - 适合看什么：理解 market data 消息模型、snapshot/incremental 和订阅方式

- Coinbase Exchange WebSocket Overview
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/websocket-feed
  - 适合看什么：理解实时行情推送接口的整体结构

- Coinbase Exchange WebSocket Channels
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/websocket-feed/channels
  - 适合看什么：看 heartbeat、level2、full 等不同频道适合解决什么问题

- Coinbase Exchange WebSocket Best Practices
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/websocket-feed/best-practices
  - 适合看什么：很适合理解慢消费者、订阅拆分、buffer、压缩、故障切换

- CME Market by Order (MBO) FAQ
  - 类型：官方文档
  - 链接：https://www.cmegroup.com/articles/faqs/market-by-order-mbo.html
  - 适合看什么：用来补逐笔订单级别行情的视角

### 公众号镜像 / 社区文章

- 新型行情中心：基于实时/历史行情的指标计算和仿真系统
  - 类型：公众号镜像
  - 链接：https://cloud.tencent.com/developer/article/2053187
  - 适合看什么：很适合理解行情中心、历史行情、仿真数据底座怎么统一

## 六、市场微观结构、执行与订单簿特征

### 官方文档

- Coinbase Matching Engine
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/matching-engine
  - 适合看什么：理解订单在簿上的行为以及成交优先级

- CME Market by Order (MBO) FAQ
  - 类型：官方文档
  - 链接：https://www.cmegroup.com/articles/faqs/market-by-order-mbo.html
  - 适合看什么：从逐笔角度理解盘口队列位置和订单可见性

### 公众号镜像 / 社区文章

- 机器学习应用在市场微观结构和高频交易的思考
  - 类型：公众号镜像
  - 链接：https://cloud.tencent.com/developer/article/2269019
  - 适合看什么：把盘口形状、撤单行为、冲击成本这些概念串起来

## 七、前置风控、会话风控与实时风控平台

### 官方文档

- Coinbase Trading
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/trading
  - 适合看什么：理解订单约束、数量和价格限制、STP 等接近前置风控的问题

- Coinbase Exchange FIX Best Practices
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/fix-api/best-practices
  - 适合看什么：理解连接、批量发单、Drop Copy 这些运行时层面的控制手段

### 公众号镜像 / 社区文章

- 实时业务风控系统
  - 类型：工程实践文章
  - 链接：https://cloud.tencent.com/developer/article/2035828
  - 适合看什么：虽然不是交易所前置风控专文，但很适合理解实时风控引擎的统计维度与规则引擎思路

- 金融风控AI引擎：实时反欺诈系统的架构设计与实现
  - 类型：工程实践文章
  - 链接：https://cloud.tencent.com/developer/article/2558035
  - 适合看什么：帮助你从“规则风控”继续往“实时特征+决策引擎”方向扩展理解

## 八、账户、持仓、台账与 Ledger

### 官方文档

- Coinbase Account Structure
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/structure
  - 适合看什么：账户、Profile、Ledger 这些概念最值得反复看

- Coinbase Trading
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/concepts/trading
  - 适合看什么：结合账户文档一起看，理解订单、成交、费用如何影响余额

### 公众号镜像 / 社区文章

- 账务系统
  - 类型：账务专题文章
  - 链接：https://cloud.tencent.com/developer/article/2532896
  - 适合看什么：适合理解账务设计理念、热点账户、日切、账务模型

- coder，你会设计交易系统吗（概念篇）？
  - 类型：交易系统文章
  - 链接：https://cloud.tencent.com/developer/article/1404448
  - 适合看什么：适合从业务视角理解交易系统、订单、账务和对账之间的关系

## 九、清算、结算、逐日盯市与对账

### 官方文档

- CME Mark-to-Market
  - 类型：官方文档
  - 链接：https://www.cmegroup.com/education/courses/introduction-to-futures/mark-to-market.html
  - 适合看什么：理解成交之后为什么还需要结算和盈亏结转

- Money Calculations for Futures and Options
  - 类型：官方文档
  - 链接：https://www.cmegroup.com/education/articles-and-reports/money-calculations-for-futures-and-options
  - 适合看什么：帮助理解保证金、结算值、现金变动是如何被计算出来的

### 公众号镜像 / 社区文章

- 图解大厂清结算系统设计
  - 类型：清结算专题文章
  - 链接：https://cloud.tencent.com/developer/article/2421837
  - 适合看什么：虽然更偏支付，但对理解账务和清结算分离、幂等、自修复特别有帮助

- 对账系统的设计咱们聊聊对账系统该如何设计
  - 类型：对账专题文章
  - 链接：https://cloud.tencent.com/developer/article/1147053
  - 适合看什么：适合理解文件获取、解析、对账、差错池这些经典模块

- 对账系统
  - 类型：对账专题文章
  - 链接：https://cloud.tencent.com/developer/article/2530704
  - 适合看什么：把交易对账、资金对账、余额调节对账分得更清楚

## 十、低延迟架构、抖动与性能调优

### 技术博客

- LMAX Technology Blog: Improving journalling latency
  - 类型：技术博客
  - 链接：https://technology.lmax.com/posts/improving-journalling-latency/
  - 适合看什么：理解为什么日志落盘会进入性能关键路径

- LMAX Technology Blog: Reducing system jitter
  - 类型：技术博客
  - 链接：https://technology.lmax.com/posts/reducing-system-jitter/
  - 适合看什么：适合理解 CPU 隔离、线程绑定、尾延迟、抖动控制

- LMAX Technology Blog: Timing is everything
  - 类型：技术博客
  - 链接：https://technology.lmax.com/posts/timing-is-everything/
  - 适合看什么：把低延迟系统的监控、追踪、根因定位讲得很实战

### 产品/技术页

- Chronicle Matching Engine
  - 类型：技术产品页
  - 链接：https://chronicle.build/matching-engine/
  - 适合看什么：可作为“撮合 + FIX + 风控 + 低延迟”一体化方案的补充视角

## 十一、恢复机制、回放、Drop Copy 与可观测性

### 官方文档

- Coinbase Exchange FIX Best Practices
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/fix-api/best-practices
  - 适合看什么：尤其值得看 `Drop Copy Session` 这部分

- Coinbase Exchange WebSocket Channels
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/websocket-feed/channels
  - 适合看什么：heartbeat、sequence、last_trade_id 这些字段对恢复和监控非常关键

- Coinbase Exchange WebSocket Best Practices
  - 类型：官方文档
  - 链接：https://docs.cdp.coinbase.com/exchange/websocket-feed/best-practices
  - 适合看什么：适合理解慢消费者、故障转移、buffer 这些运行问题

### 技术博客

- LMAX Technology Blog: Timing is everything
  - 类型：技术博客
  - 链接：https://technology.lmax.com/posts/timing-is-everything/
  - 适合看什么：适合理解 tracing、延迟分段测量和根因定位

- LMAX Technology Blog: Improving journalling latency
  - 类型：技术博客
  - 链接：https://technology.lmax.com/posts/improving-journalling-latency/
  - 适合看什么：帮助理解“恢复能力为什么会反过来决定主链路设计”

## 十二、回测、仿真、实盘一致性

### 官方/社区资料

- VeighNa 官网
  - 类型：官方站点
  - 链接：https://www.vnpy.com/
  - 适合看什么：先建立 VeighNa 的模块图和能力边界

- VeighNa Demo
  - 类型：产品 Demo
  - 链接：https://www.vnpy.com/demo
  - 适合看什么：看一套成熟框架里，回测、实盘、风控、数据录制是怎样放在一起的

- CTA 自动交易模块文档
  - 类型：官方文档
  - 链接：https://www.vnpy.com/docs/cn/community/app/cta_strategy.html
  - 适合看什么：适合理解订单回报、成交回报、策略回调在框架中的角色

- 交易接口文档
  - 类型：官方文档
  - 链接：https://www.vnpy.com/docs/cn/community/info/gateway.html
  - 适合看什么：理解 gateway、连接配置、账户查询、持仓查询这些实盘工程要素

- PortfolioStrategy 文档
  - 类型：官方文档
  - 链接：https://www.vnpy.com/docs/cn/community/app/portfolio_strategy.html
  - 适合看什么：看加载历史数据、同步策略状态、实盘持久化这些细节

- 期权策略交易文档
  - 类型：官方文档
  - 链接：https://www.vnpy.com/docs/cn/elite/strategy/elite_optionstrategy.html
  - 适合看什么：很适合理解“回测支持什么、实盘支持什么”这种不一致问题

## 十三、开源实现与源码阅读入口

### 开源项目

- exchange-core
  - 类型：开源项目
  - 链接：https://github.com/exchange-core/exchange-core
  - 适合看什么：适合作为高性能撮合引擎的源码阅读入口

- VeighNa / vn.py
  - 类型：开源项目
  - 链接：https://github.com/vnpy/vnpy
  - 适合看什么：适合作为“策略框架 + Gateway + 回测/实盘”的源码阅读入口

### 配套文章

- 数字货币交易所开发常用的 7 个开源撮合引擎
  - 类型：开源项目综述
  - 链接：https://cloud.tencent.com/developer/article/1524188
  - 适合看什么：快速建立“有哪些开源撮合引擎可读”的地图

- match-trade 超高效的交易所撮合引擎
  - 类型：工程实践文章
  - 链接：https://cloud.tencent.com/developer/article/1576713
  - 适合看什么：看基于 Disruptor 和内存结构的撮合实现思路

## 十四、如果你只想挑“最值得先读”的 15 篇

下面这 15 篇适合按顺序读，能比较快地把交易系统主线搭起来：

1. Coinbase Concepts Overview  
   https://docs.cdp.coinbase.com/exchange/concepts/overview
2. Coinbase Trading  
   https://docs.cdp.coinbase.com/exchange/concepts/trading
3. Coinbase Account Structure  
   https://docs.cdp.coinbase.com/exchange/concepts/structure
4. Coinbase Matching Engine  
   https://docs.cdp.coinbase.com/exchange/concepts/matching-engine
5. Nasdaq OUCH  
   https://www.nasdaqtrader.com/Trader.aspx?id=OUCH
6. Nasdaq TotalView-ITCH 5.0 Specification  
   https://nasdaqtrader.com/content/technicalsupport/specifications/dataproducts/NQTVITCHSpecification.pdf
7. Coinbase Exchange FIX API Connectivity  
   https://docs.cdp.coinbase.com/exchange/fix-api/connectivity
8. Coinbase Exchange FIX Best Practices  
   https://docs.cdp.coinbase.com/exchange/fix-api/best-practices
9. Coinbase Exchange FIX Market Data  
   https://docs.cdp.coinbase.com/exchange/fix-api/market-data
10. CME Market by Order (MBO) FAQ  
    https://www.cmegroup.com/articles/faqs/market-by-order-mbo.html
11. CME Mark-to-Market  
    https://www.cmegroup.com/education/courses/introduction-to-futures/mark-to-market.html
12. 新型行情中心：基于实时/历史行情的指标计算和仿真系统  
    https://cloud.tencent.com/developer/article/2053187
13. 机器学习应用在市场微观结构和高频交易的思考  
    https://cloud.tencent.com/developer/article/2269019
14. LMAX Technology Blog: Improving journalling latency  
    https://technology.lmax.com/posts/improving-journalling-latency/
15. LMAX Technology Blog: Timing is everything  
    https://technology.lmax.com/posts/timing-is-everything/

## 十五、按目标反查资料

### 如果你现在最想搞懂“交易系统业务全景”

- Coinbase Concepts Overview
- Coinbase Trading
- 算法交易系统架构，此篇足矣！
- 交易系统架构演进之路（一）（二）（三）

### 如果你现在最想搞懂“订单和撮合”

- Coinbase Matching Engine
- Nasdaq OUCH
- 撮合系统是什么？把“买卖双方配对成成交”的那台发动机到底做了什么
- 交易所撮合引擎原理及实现代码

### 如果你现在最想搞懂“行情系统与本地订单簿”

- Nasdaq TotalView-ITCH 5.0 Specification
- Coinbase Exchange FIX Market Data
- Coinbase Exchange WebSocket Channels
- 新型行情中心：基于实时/历史行情的指标计算和仿真系统

### 如果你现在最想搞懂“风控、账务、结算”

- Coinbase Account Structure
- CME Mark-to-Market
- 图解大厂清结算系统设计
- 对账系统

### 如果你现在最想搞懂“低延迟与恢复”

- LMAX Improving journalling latency
- LMAX Reducing system jitter
- LMAX Timing is everything
- exchange-core

## 十六、后续还可以继续补的专题

如果后面要把这份清单继续扩成 `v2`，建议新增 4 个专题：

- `FIX 字段逐项讲解清单`
- `ITCH / WebSocket / MBO 行情协议对照清单`
- `账务、台账、清结算、对账专题清单`
- `开源交易系统源码阅读顺序清单`
