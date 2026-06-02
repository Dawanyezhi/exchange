# Common 模块指南

`common` 是 exchange Maven reactor 的共享协议模块。主代码位于 `src/main/java/com/laser/exchange/common`；SBE schema 位于 `src/main/resources/sbe`。

从仓库根目录执行模块命令：

```bash
mvn -pl common generate-sources
mvn -pl common test
```

SBE 会把 Java codec 生成到 `common/target/generated-sources/sbe`，包名包括：

- `com.laser.exchange.common.codec`
- `com.laser.exchange.common.codec.snapshot`

不要编辑生成源码。SBE schema、枚举、request/result DTO 变更都应视为兼容性敏感变更，并在同一次修改中同步更新 `matching` 和 `counter` 的 import、解码逻辑和测试。

每次回答都必须使用中文，除非用户明确要求使用其他语言。
