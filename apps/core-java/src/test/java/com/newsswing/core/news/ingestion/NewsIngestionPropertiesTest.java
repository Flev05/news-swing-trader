package com.newsswing.core.news.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NewsIngestionPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void defaultsPendingCapacityToOneThousand() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(NewsIngestionProperties.class).pendingCapacity())
              .isEqualTo(1000);
        });
  }

  @Test
  void permitsAnExplicitZeroPendingCapacity() {
    contextRunner
        .withPropertyValues("trader.news.ingestion.pending-capacity=0")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(NewsIngestionProperties.class).pendingCapacity()).isZero();
            });
  }

  @Test
  void rejectsANegativePendingCapacityAtStartup() {
    contextRunner
        .withPropertyValues("trader.news.ingestion.pending-capacity=-1")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(IllegalArgumentException.class)
                  .rootCause()
                  .hasMessage("trader.news.ingestion.pending-capacity must be non-negative");
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(NewsIngestionProperties.class)
  static class PropertiesConfiguration {}
}
