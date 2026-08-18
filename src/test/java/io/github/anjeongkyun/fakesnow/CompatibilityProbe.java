package io.github.anjeongkyun.fakesnow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Probes what actually works, to build an honest compatibility list. Never fails the build — it
 * prints a pass/fail table so unsupported behaviour can be documented rather than discovered later.
 */
@Testcontainers
class CompatibilityProbe {

    @Container
    private static final FakeSnowContainer FAKESNOW = new FakeSnowContainer();

    private final List<String> results = new ArrayList<>();

    private Connection connect() throws Exception {
        return DriverManager.getConnection(FAKESNOW.getJdbcUrl(), FAKESNOW.getUsername(), FAKESNOW.getPassword());
    }

    private void probe(String label, ThrowingRunnable body) {
        try {
            body.run();
            results.add(String.format("  PASS  %s", label));
        } catch (Throwable t) {
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            results.add(String.format("  FAIL  %-38s %s", label, msg.replace('\n', ' ').trim()));
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("unexpected result: " + what);
        }
    }

    @Test
    void probeAll() throws Exception {
        try (Connection conn = connect();
            Statement stmt = conn.createStatement()) {

            probe("MERGE", () -> {
                stmt.execute("CREATE OR REPLACE TABLE m (id INT, v INT)");
                stmt.executeUpdate("INSERT INTO m VALUES (1, 10)");
                int n = stmt.executeUpdate(
                    "MERGE INTO m t USING (SELECT 1 AS id, 99 AS v UNION ALL SELECT 2, 20) s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET t.v = s.v "
                        + "WHEN NOT MATCHED THEN INSERT (id, v) VALUES (s.id, s.v)");
                expect(n == 2, "merge affected " + n + ", expected 2 (1 inserted + 1 updated)");
            });

            probe("recursive CTE", () -> {
                try (ResultSet rs = stmt.executeQuery(
                    "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t WHERE n < 5) "
                        + "SELECT count(*) FROM t")) {
                    rs.next();
                    expect(rs.getInt(1) == 5, "got " + rs.getInt(1));
                }
            });

            probe("DATEADD / DATEDIFF", () -> {
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT DATEADD(day, 3, '2026-01-01'::DATE), DATEDIFF(day, '2026-01-01'::DATE, '2026-01-11'::DATE)")) {
                    rs.next();
                    expect(rs.getInt(2) == 10, "datediff=" + rs.getInt(2));
                }
            });

            probe("DATE_TRUNC + date_part names", () -> {
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT DATE_TRUNC('month', '2026-03-15'::DATE), DATE_TRUNC(quarter, '2026-03-15'::DATE)")) {
                    rs.next();
                }
            });

            probe("TIMESTAMP_NTZ / LTZ / TZ columns", () -> {
                stmt.execute("CREATE OR REPLACE TABLE ts (a TIMESTAMP_NTZ, b TIMESTAMP_LTZ, c TIMESTAMP_TZ)");
                stmt.executeUpdate("INSERT INTO ts VALUES ('2026-01-01 10:00:00', '2026-01-01 10:00:00', "
                    + "'2026-01-01 10:00:00 +09:00')");
                try (ResultSet rs = stmt.executeQuery("SELECT a, b, c FROM ts")) {
                    rs.next();
                    expect(rs.getString(3) != null, "tz column null");
                }
            });

            // the literal case above uses an offset, which is a different code path to binding a
            // value with setTimestamp. bind it too, since that is what an ORM does.
            probe("setTimestamp / setDate binding", () -> {
                stmt.execute("CREATE OR REPLACE TABLE tsb (a TIMESTAMP_NTZ, b TIMESTAMP_LTZ, c DATE)");
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO tsb VALUES (?, ?, ?)")) {
                    ps.setTimestamp(1, Timestamp.valueOf("2026-01-01 10:00:00"));
                    ps.setTimestamp(2, Timestamp.valueOf("2026-01-01 10:00:00"));
                    ps.setDate(3, java.sql.Date.valueOf("2026-01-01"));
                    ps.executeUpdate();
                }
                try (ResultSet rs = stmt.executeQuery("SELECT a, b, c FROM tsb")) {
                    rs.next();
                    expect(rs.getTimestamp(1) != null, "ntz column null");
                    expect(rs.getTimestamp(2) != null, "ltz column null");
                }
            });

            probe("LISTAGG / IFF / NVL / SPLIT_PART", () -> {
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT LISTAGG(x, ',') , IFF(1=1,'y','n'), NVL(NULL, 'd'), SPLIT_PART('a,b,c', ',', 2) "
                        + "FROM (SELECT 'p' AS x UNION ALL SELECT 'q')")) {
                    rs.next();
                }
            });

            probe("OBJECT_CONSTRUCT / ARRAY_AGG", () -> {
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT OBJECT_CONSTRUCT('k', 1), ARRAY_AGG(n) FROM (SELECT 1 AS n UNION ALL SELECT 2)")) {
                    rs.next();
                }
            });

            probe("REGEXP_SUBSTR / REGEXP_REPLACE", () -> {
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT REGEXP_SUBSTR('abc123', '[0-9]+'), REGEXP_REPLACE('a-b-c', '-', '+')")) {
                    rs.next();
                    expect("123".equals(rs.getString(1)), "regexp_substr=" + rs.getString(1));
                }
            });

            probe("TRY_CAST / TRY_TO_NUMBER", () -> {
                try (ResultSet rs = stmt.executeQuery("SELECT TRY_CAST('x' AS INT), TRY_TO_NUMBER('x')")) {
                    rs.next();
                    expect(rs.getObject(1) == null, "try_cast should be null");
                }
            });

            probe("batch insert (executeBatch)", () -> {
                stmt.execute("CREATE OR REPLACE TABLE b (id INT)");
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO b VALUES (?)")) {
                    for (int i = 1; i <= 3; i++) {
                        ps.setInt(1, i);
                        ps.addBatch();
                    }
                    int[] counts = ps.executeBatch();
                    expect(counts.length == 3, "batch counts length=" + counts.length);
                }
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM b")) {
                    rs.next();
                    expect(rs.getInt(1) == 3, "rows=" + rs.getInt(1));
                }
            });

            probe("transaction rollback", () -> {
                stmt.execute("CREATE OR REPLACE TABLE tx (id INT)");
                conn.setAutoCommit(false);
                try {
                    stmt.executeUpdate("INSERT INTO tx VALUES (1)");
                    conn.rollback();
                    try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM tx")) {
                        rs.next();
                        expect(rs.getInt(1) == 0, "rollback left " + rs.getInt(1) + " rows");
                    }
                } finally {
                    conn.setAutoCommit(true);
                }
            });

            probe("savepoint", () -> {
                conn.setAutoCommit(false);
                try {
                    Savepoint sp = conn.setSavepoint("sp1");
                    conn.rollback(sp);
                } finally {
                    conn.setAutoCommit(true);
                }
            });

            probe("getGeneratedKeys", () -> {
                stmt.execute("CREATE OR REPLACE TABLE gk (id INT AUTOINCREMENT, name VARCHAR)");
                try (PreparedStatement ps =
                    conn.prepareStatement("INSERT INTO gk (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, "x");
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        expect(keys.next(), "no generated keys returned");
                    }
                }
            });

            probe("QUALIFY with alias", () -> {
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT n, ROW_NUMBER() OVER (ORDER BY n) rn FROM (SELECT 1 AS n UNION ALL SELECT 2) QUALIFY rn = 1")) {
                    rs.next();
                }
            });

            probe("PIVOT", () -> {
                stmt.execute("CREATE OR REPLACE TABLE p (k VARCHAR, v INT)");
                stmt.executeUpdate("INSERT INTO p VALUES ('a', 1), ('b', 2)");
                try (ResultSet rs =
                    stmt.executeQuery("SELECT * FROM p PIVOT(SUM(v) FOR k IN ('a', 'b'))")) {
                    rs.next();
                }
            });

            probe("information_schema.columns", () -> {
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT count(*) FROM information_schema.columns WHERE table_name = 'M'")) {
                    rs.next();
                    expect(rs.getInt(1) > 0, "no columns found");
                }
            });

            probe("DatabaseMetaData.getPrimaryKeys", () -> {
                stmt.execute("CREATE OR REPLACE TABLE pk (id INT PRIMARY KEY)");
                try (ResultSet rs = conn.getMetaData().getPrimaryKeys(null, null, "PK")) {
                    expect(rs.next(), "no primary keys returned");
                }
            });

            probe("large result (1000 rows)", () -> {
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT seq4() AS n FROM TABLE(GENERATOR(ROWCOUNT => 1000))")) {
                    int c = 0;
                    while (rs.next()) {
                        c++;
                    }
                    expect(c == 1000, "rows=" + c);
                }
            });
        }

        System.out.println("\n===== fakesnow compatibility probe =====");
        results.forEach(System.out::println);
        System.out.println("========================================\n");
    }
}
