# testcontainers-fakesnow

A [Testcontainers](https://testcontainers.com) module for [fakesnow](https://github.com/tekumara/fakesnow), so JVM projects can run integration tests against Snowflake-flavoured SQL without a Snowflake account.

fakesnow speaks enough of Snowflake's REST protocol that the official `snowflake-jdbc` driver connects to it unmodified. This module wraps it as a `JdbcDatabaseContainer`.

```java
@Testcontainers
class OrdersRepositoryTest {

    @Container
    static final FakeSnowContainer fakesnow = new FakeSnowContainer();

    @Test
    void findsRecentOrders() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                fakesnow.getJdbcUrl(), fakesnow.getUsername(), fakesnow.getPassword())) {
            // VARIANT, LATERAL FLATTEN, QUALIFY all work
        }
    }
}
```

## Why not just substitute Postgres or H2

Because the moment your SQL uses anything Snowflake-specific (`VARIANT`, `LATERAL FLATTEN`, `QUALIFY`, `MERGE INTO`, Snowflake's date semantics), a substitute database stops telling you the truth. Tests pass locally and the same SQL behaves differently in production.

The alternative that does keep fidelity is LocalStack's Snowflake emulator, which is commercially licensed. fakesnow is Apache-2.0.

## Install

Requires Java 17+ and a Docker environment Testcontainers can reach.

```kotlin
testImplementation("io.github.anjeongkyun:testcontainers-fakesnow:0.1.0")
testImplementation("net.snowflake:snowflake-jdbc:3.19.0")
```

```xml
<dependency>
    <groupId>io.github.anjeongkyun</groupId>
    <artifactId>testcontainers-fakesnow</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

The Snowflake driver is not a transitive dependency, so bring the version you use in production.

## Required JVM flag

Add `--add-opens=java.base/java.nio=ALL-UNNAMED` to the test JVM. snowflake-jdbc bundles Arrow, which reflects into `java.nio`; without the flag the first query fails with `ExceptionInInitializerError`.

```kotlin
tasks.withType<Test> {
    jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
}
```

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>--add-opens=java.base/java.nio=ALL-UNNAMED</argLine>
    </configuration>
</plugin>
```

## Spring Boot

```java
@DynamicPropertySource
static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", fakesnow::getJdbcUrl);
    registry.add("spring.datasource.username", fakesnow::getUsername);
    registry.add("spring.datasource.password", fakesnow::getPassword);
    registry.add("spring.datasource.driver-class-name", fakesnow::getDriverClassName);
}
```

## Configuration

```java
new FakeSnowContainer("ghcr.io/tekumara/fakesnow:0.11.13")
    .withDatabaseName("analytics")
    .withSchemaName("staging")
    .withAccount("acme")        // fakesnow accepts any account
    .withUsername("fake")       // and any credentials
    .withPassword("snow");
```

`getJdbcUrl()` always includes `account`. Leaving it out makes the driver derive the account from the host, which fails for `localhost` with a misleading "claims to not accept jdbcUrl" error rather than a connection failure.

## Compatibility

Two things depend on the fakesnow version:

- `executeUpdate`, and therefore `JdbcTemplate.update()`, needs **0.11.12 or newer**. Earlier versions rejected every DDL and DML statement sent through it.
- `setTimestamp`, and therefore any ORM writing a timestamp, needs **0.11.13 or newer**. Earlier versions returned a 500 for every timestamp column, because the driver binds `setTimestamp` as `TIMESTAMP_LTZ` and only `TIMESTAMP_NTZ` was handled ([fakesnow#374](https://github.com/tekumara/fakesnow/issues/374)).

Measured against fakesnow 0.11.13 with snowflake-jdbc 3.19.0 (`CompatibilityProbe` in the test sources reproduces this):

| | |
|---|---|
| `MERGE`, recursive CTEs, `PIVOT`, `QUALIFY` (incl. select aliases) | works |
| `DATEADD`, `DATEDIFF`, `DATE_TRUNC` | works |
| `VARIANT` path access, `LATERAL FLATTEN`, `OBJECT_CONSTRUCT`, `ARRAY_AGG` | works |
| `LISTAGG`, `IFF`, `NVL`, `SPLIT_PART`, `REGEXP_*`, `TRY_CAST`, `TRY_TO_NUMBER` | works |
| `information_schema`, transaction rollback, 1000-row results | works |
| `setTimestamp`, `setDate` binding | works, needs 0.11.13 |
| **batch insert** (`executeBatch`) | **fails**, [fakesnow#371](https://github.com/tekumara/fakesnow/issues/371) |
| **`TIMESTAMP_TZ` with an offset literal** | **fails**, [fakesnow#372](https://github.com/tekumara/fakesnow/issues/372) |
| **`DatabaseMetaData.getPrimaryKeys`** | **fails**, [fakesnow#373](https://github.com/tekumara/fakesnow/issues/373) |

All three failures have fixes merged or in review upstream, so they should clear in a later fakesnow release: `getPrimaryKeys` is fixed on main, the offset literal is narrowed by [fakesnow#376](https://github.com/tekumara/fakesnow/pull/376) (a bare literal in `INSERT ... VALUES` still fails, there is no cast to rewrite from), and batch insert needs [fakesnow#383](https://github.com/tekumara/fakesnow/pull/383) and [#384](https://github.com/tekumara/fakesnow/pull/384).

Savepoints and `getGeneratedKeys` also fail, but that is **not** a fakesnow gap. The Snowflake driver reports `supportsSavepoints() == false` and `supportsGetGeneratedKeys() == false`, so they don't work against real Snowflake either. Hibernate's nested transactions (which use savepoints) are unavailable on Snowflake generally.

Beyond that, see fakesnow's own [implementation coverage](https://github.com/tekumara/fakesnow#implementation-coverage). Note it accepts a more liberal dialect than real Snowflake, so it can pass SQL that Snowflake would reject.

## License

Apache-2.0. This project is not affiliated with Snowflake Inc. or the fakesnow maintainers.
