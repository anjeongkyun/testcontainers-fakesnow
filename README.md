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

Because the moment your SQL uses anything Snowflake-specific — `VARIANT`, `LATERAL FLATTEN`, `QUALIFY`, `MERGE INTO`, Snowflake's date semantics — a substitute database stops telling you the truth. Tests pass locally and the same SQL behaves differently in production.

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

The Snowflake driver is not a transitive dependency — bring the version you use in production.

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
new FakeSnowContainer("ghcr.io/tekumara/fakesnow:0.11.12")
    .withDatabaseName("analytics")
    .withSchemaName("staging")
    .withAccount("acme")        // fakesnow accepts any account
    .withUsername("fake")       // and any credentials
    .withPassword("snow");
```

`getJdbcUrl()` always includes `account`. Leaving it out makes the driver derive the account from the host, which fails for `localhost` with a misleading "claims to not accept jdbcUrl" error rather than a connection failure.

## Compatibility

`executeUpdate` — and therefore `JdbcTemplate.update()`, Hibernate and Flyway — needs fakesnow **0.11.12 or newer**. Earlier versions rejected every DDL and DML statement sent through it.

Everything else is fakesnow's own coverage; see its [implementation coverage](https://github.com/tekumara/fakesnow#implementation-coverage) and caveats. Notably fakesnow accepts a more liberal dialect than real Snowflake, so it can pass SQL that Snowflake would reject.

## License

Apache-2.0. This project is not affiliated with Snowflake Inc. or the fakesnow maintainers.
