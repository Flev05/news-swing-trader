package com.newsswing.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class V1ToM2aMigrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  @Test
  void upgradesAnExistingV1DatabaseToM2a() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .defaultSchema("trader")
        .schemas("trader")
        .locations("classpath:db/migration")
        .target("1")
        .load()
        .migrate();

    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement();
        var results =
            statement.executeQuery(
                "SELECT metadata_value FROM trader.platform_metadata WHERE metadata_key = 'milestone'")) {
      results.next();
      assertThat(results.getString(1)).isEqualTo("0");
    }

    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .defaultSchema("trader")
        .schemas("trader")
        .locations("classpath:db/migration")
        .target("2")
        .load()
        .migrate();

    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement();
        var results =
            statement.executeQuery(
                "SELECT metadata_value FROM trader.platform_metadata WHERE metadata_key = 'milestone'")) {
      results.next();
      assertThat(results.getString(1)).isEqualTo("2A");
      assertThat(
              statement.executeQuery(
                  "SELECT COUNT(*) FROM information_schema.tables "
                      + "WHERE table_schema = 'trader' AND table_name = 'news_revisions'"))
          .satisfies(
              tableResults -> {
                try {
                  tableResults.next();
                  assertThat(tableResults.getInt(1)).isEqualTo(1);
                } catch (Exception exception) {
                  throw new AssertionError(exception);
                }
              });
    }
  }
}
