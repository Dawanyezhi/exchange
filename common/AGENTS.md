# Common Module Guidelines

`common` is the shared protocol module for the exchange reactor. Main code lives under `src/main/java/com/laser/exchange/common`; SBE schemas live in `src/main/resources/sbe`.

Run module commands from the repository root:

```bash
mvn -pl common generate-sources
mvn -pl common test
```

SBE generates Java codecs into `common/target/generated-sources/sbe` with packages:

- `com.laser.exchange.common.codec`
- `com.laser.exchange.common.codec.snapshot`

Do not edit generated sources. Treat SBE schema, enum, and request/result DTO changes as compatibility-sensitive and update `matching` and `counter` imports/tests in the same change.
