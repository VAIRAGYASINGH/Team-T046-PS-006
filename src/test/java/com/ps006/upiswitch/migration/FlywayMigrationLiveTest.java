package com.ps006.upiswitch.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live database migration and constraint validation test using Testcontainers PostgreSQL.
 * Verifies that all 5 Flyway migrations apply cleanly, schema integrity is enforced,
 * and database constraints reject invalid financial operations.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class FlywayMigrationLiveTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("upi_switch_test")
            .withUsername("switch_user")
            .withPassword("switch_secret");

    private static Flyway flyway;

    @BeforeAll
    static void setupFlyway() {
        if (postgres != null && postgres.isRunning()) {
            flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Verify clean execution of all 5 Flyway migrations")
    void testAllMigrationsApplyCleanly() {
        Assumptions.assumeTrue(postgres != null && postgres.isRunning(), "Docker must be running for live PostgreSQL test");
        int applied = flyway.migrate().migrationsExecuted;
        assertEquals(5, applied, "All 5 migrations (V1 through V5) must execute cleanly");

        MigrationInfo[] info = flyway.info().all();
        assertEquals(5, info.length);
        for (MigrationInfo mi : info) {
            assertTrue(mi.getState().isApplied(), "Migration " + mi.getScript() + " must be applied");
        }
    }

    @Test
    @Order(2)
    @DisplayName("Verify idempotent migration on application restart")
    void testMigrationIdempotencyOnRestart() {
        Assumptions.assumeTrue(postgres != null && postgres.isRunning(), "Docker must be running for live PostgreSQL test");
        int appliedOnRestart = flyway.migrate().migrationsExecuted;
        assertEquals(0, appliedOnRestart, "Restarting migration on already migrated DB must execute 0 migrations");
    }

    @Test
    @Order(3)
    @DisplayName("Verify balance non-negative check constraint rejects overdraft")
    void testNegativeBalanceCheckConstraint() throws SQLException {
        Assumptions.assumeTrue(postgres != null && postgres.isRunning(), "Docker must be running for live PostgreSQL test");
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO accounts (account_number, ifsc, balance, currency) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, "123456789012");
            ps.setString(2, "SBIN0001234");
            ps.setBigDecimal(3, new BigDecimal("-100.00")); // Negative balance
            ps.setString(4, "INR");

            SQLException ex = assertThrows(SQLException.class, ps::executeUpdate,
                    "Database check constraint must reject negative account balance");
            assertTrue(ex.getMessage().contains("chk_account_balance_non_negative") || ex.getMessage().contains("check"),
                    "Error must indicate check constraint violation");
        }
    }

    @Test
    @Order(4)
    @DisplayName("Verify clean recreation of entire schema from scratch")
    void testCleanSchemaRecreation() {
        Assumptions.assumeTrue(postgres != null && postgres.isRunning(), "Docker must be running for live PostgreSQL test");
        flyway.clean();
        int recreated = flyway.migrate().migrationsExecuted;
        assertEquals(5, recreated, "Entire schema must be recreated cleanly from V1 through V5");
    }
}

