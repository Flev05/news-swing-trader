package com.newsswing.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class MigrationSmokeTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6");

  @DynamicPropertySource
  static void configureFlywaySchema(DynamicPropertyRegistry registry) {
    registry.add("spring.flyway.default-schema", () -> "trader");
    registry.add("spring.flyway.schemas", () -> "trader");
  }

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void appliesTheM2aPersistenceMigration() {
    String milestone =
        jdbcTemplate.queryForObject(
            "SELECT metadata_value FROM trader.platform_metadata WHERE metadata_key = 'milestone'",
            String.class);
    assertThat(milestone).isEqualTo("2A");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = 'trader' "
                    + "AND table_name IN ('news_items', 'news_revisions', 'inference_jobs', "
                    + "'queue_admission_locks')",
                Integer.class))
        .isEqualTo(4);
  }
}
