package io.github.anjeongkyun.fakesnow;

import java.time.Duration;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A {@link JdbcDatabaseContainer} for <a href="https://github.com/tekumara/fakesnow">fakesnow</a>,
 * which the official {@code snowflake-jdbc} driver can connect to unmodified.
 *
 * <p>The JVM running the tests needs {@code --add-opens=java.base/java.nio=ALL-UNNAMED}. Without it
 * the Arrow bundled in snowflake-jdbc cannot initialise and queries fail with
 * {@code ExceptionInInitializerError}.
 *
 * <pre>{@code
 * @Container
 * static final FakeSnowContainer fakesnow = new FakeSnowContainer();
 *
 * @DynamicPropertySource
 * static void datasource(DynamicPropertyRegistry registry) {
 *     registry.add("spring.datasource.url", fakesnow::getJdbcUrl);
 *     registry.add("spring.datasource.username", fakesnow::getUsername);
 *     registry.add("spring.datasource.password", fakesnow::getPassword);
 *     registry.add("spring.datasource.driver-class-name", fakesnow::getDriverClassName);
 * }
 * }</pre>
 */
public class FakeSnowContainer extends JdbcDatabaseContainer<FakeSnowContainer> {

    /** Database type name, for use with {@code jdbc:tc:} style urls. */
    public static final String NAME = "fakesnow";

    /** The port fakesnow's docker image serves on. */
    public static final int PORT = 64616;

    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("ghcr.io/tekumara/fakesnow");

    private static final String DEFAULT_TAG = "0.11.12";

    private String databaseName = "db1";

    private String schemaName = "schema1";

    private String account = "fakesnow";

    private String username = "fake";

    private String password = "snow";

    /** Uses the fakesnow image this module was built and tested against. */
    public FakeSnowContainer() {
        this(DEFAULT_IMAGE_NAME.withTag(DEFAULT_TAG));
    }

    /**
     * @param dockerImageName a fakesnow image, eg: {@code ghcr.io/tekumara/fakesnow:0.11.12}
     */
    public FakeSnowContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    /**
     * @param dockerImageName a fakesnow image, eg: {@code ghcr.io/tekumara/fakesnow:0.11.12}
     */
    public FakeSnowContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DEFAULT_IMAGE_NAME);
        addExposedPort(PORT);
        waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    }

    @Override
    public String getDriverClassName() {
        return "net.snowflake.client.jdbc.SnowflakeDriver";
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code account} is always included. The driver otherwise derives it from the host, which
     * fails for hosts with no account part such as {@code localhost}, and reports the confusing
     * "claims to not accept jdbcUrl" rather than a connection error.
     */
    @Override
    public String getJdbcUrl() {
        return "jdbc:snowflake://"
            + getHost()
            + ":"
            + getMappedPort(PORT)
            + "/?ssl=off&account="
            + account
            + "&db="
            + databaseName
            + "&schema="
            + schemaName
            + constructUrlParameters("&", "&");
    }

    @Override
    public String getDatabaseName() {
        return databaseName;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    protected String getTestQueryString() {
        return "SELECT 1";
    }

    @Override
    public FakeSnowContainer withDatabaseName(String databaseName) {
        this.databaseName = databaseName;
        return self();
    }

    /** fakesnow accepts any credentials, so this only affects what the driver sends. */
    @Override
    public FakeSnowContainer withUsername(String username) {
        this.username = username;
        return self();
    }

    /** fakesnow accepts any credentials, so this only affects what the driver sends. */
    @Override
    public FakeSnowContainer withPassword(String password) {
        this.password = password;
        return self();
    }

    /**
     * Sets the schema fakesnow creates on connect, and the one in the JDBC url.
     *
     * @param schemaName schema to connect to
     * @return this container
     */
    public FakeSnowContainer withSchemaName(String schemaName) {
        this.schemaName = schemaName;
        return self();
    }

    /**
     * fakesnow accepts any account, so this only affects what the driver sends.
     *
     * @param account account name to put in the JDBC url
     * @return this container
     */
    public FakeSnowContainer withAccount(String account) {
        this.account = account;
        return self();
    }

    /**
     * @return the schema in the JDBC url
     */
    public String getSchemaName() {
        return schemaName;
    }

    /**
     * @return the account in the JDBC url
     */
    public String getAccount() {
        return account;
    }
}
