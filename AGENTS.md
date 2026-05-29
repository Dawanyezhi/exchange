# Repository Guidelines

## Project Structure

This is a Java 17 Maven reactor for a centralized exchange:

- `common`: protocol DTOs, enums, utility classes, and SBE schemas/codecs.
- `matching`: Spring Boot matching-engine service with Aeron Cluster, snapshots, validation, and result broadcast.
- `counter`: Spring Boot mock counter/client service that sends encoded commands to the matching cluster.

Packages live under `com.laser.exchange`:

- `com.laser.exchange.common`
- `com.laser.exchange.matching`
- `com.laser.exchange.counter`

## Build And Test

Run commands from the repository root:

```bash
mvn test
mvn package -DskipTests
mvn -pl common generate-sources
mvn -pl matching -am test
mvn -pl counter -am test
```

SBE schemas are in `common/src/main/resources/sbe`; generated Java sources go to `common/target/generated-sources/sbe`. Do not edit generated sources.

## Local Conventions

Keep the existing Java style and Aeron JVM module flags. Treat SBE schema, enum, and wire-format changes as compatibility-sensitive and update all dependent modules in the same change.

When answering questions or proposing changes, use the perspective of a senior trading-system engineer and prioritize production-grade solutions appropriate for exchange systems.

Do not commit `target/`, local logs, pids, or IDE metadata.
