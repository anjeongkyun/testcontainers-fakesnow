package io.github.anjeongkyun.fakesnow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FakeSnowContainerTest {

    @Container
    private static final FakeSnowContainer FAKESNOW = new FakeSnowContainer();

    private Connection connect() throws Exception {
        return DriverManager.getConnection(FAKESNOW.getJdbcUrl(), FAKESNOW.getUsername(), FAKESNOW.getPassword());
    }

    @Test
    void connects_with_the_real_snowflake_driver() throws Exception {
        try (Connection conn = connect();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertThat(conn.getMetaData().getDriverName()).containsIgnoringCase("snowflake");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void reports_affected_rows_from_executeUpdate() throws Exception {
        try (Connection conn = connect();
            Statement stmt = conn.createStatement()) {
            assertThat(stmt.executeUpdate("CREATE OR REPLACE TABLE affected (id INT)")).isZero();
            assertThat(stmt.executeUpdate("INSERT INTO affected VALUES (1), (2), (3)")).isEqualTo(3);
            assertThat(stmt.executeUpdate("UPDATE affected SET id = 9 WHERE id > 1")).isEqualTo(2);
            assertThat(stmt.executeUpdate("DELETE FROM affected WHERE id = 9")).isEqualTo(2);
            assertThat(stmt.executeUpdate("USE SCHEMA " + FAKESNOW.getSchemaName())).isZero();
        }
    }

    @Test
    void binds_parameters() throws Exception {
        try (Connection conn = connect();
            Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE TABLE bound (id INT, name VARCHAR)");

            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bound VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "hello");
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM bound WHERE id = ?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).isEqualTo("hello");
                }
            }
        }
    }

    /** The reason for using fakesnow over a Postgres substitute: these have no Postgres equivalent. */
    @Test
    void runs_snowflake_only_syntax() throws Exception {
        try (Connection conn = connect();
            Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE OR REPLACE TABLE events (id INT, payload VARIANT)");
            stmt.executeUpdate("INSERT INTO events SELECT 1, PARSE_JSON('{\"tags\":[10,20,30]}')");

            try (ResultSet rs = stmt.executeQuery("SELECT payload:tags[0]::INT FROM events")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(10);
            }
            // no ordering guarantee without ORDER BY, same as real Snowflake
            try (ResultSet rs = stmt.executeQuery(
                "SELECT f.value::INT AS tag FROM events, LATERAL FLATTEN(input => payload:tags) f ORDER BY tag")) {
                List<Integer> tags = new ArrayList<>();
                while (rs.next()) {
                    tags.add(rs.getInt(1));
                }
                assertThat(tags).containsExactly(10, 20, 30);
            }
            try (ResultSet rs = stmt.executeQuery(
                "SELECT id FROM events QUALIFY ROW_NUMBER() OVER (ORDER BY id) = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void exposes_configured_names_in_the_url() {
        try (FakeSnowContainer container =
            new FakeSnowContainer().withDatabaseName("analytics").withSchemaName("staging").withAccount("acme")) {
            assertThat(container.getDatabaseName()).isEqualTo("analytics");
            assertThat(container.getSchemaName()).isEqualTo("staging");
            assertThat(container.getAccount()).isEqualTo("acme");
        }
    }
}
