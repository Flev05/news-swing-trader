package com.newsswing.core.news.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NewsApiPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void defaultsToOneMebibyte() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(NewsApiProperties.class).maxRequestBytes())
              .isEqualTo(1_048_576);
        });
  }

  @Test
  void permitsAValidOverride() {
    contextRunner
        .withPropertyValues("trader.news.api.max-request-bytes=1024")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(NewsApiProperties.class).maxRequestBytes())
                  .isEqualTo(1024);
            });
  }

  @Test
  void rejectsValuesOutsideTheAllowedRange() {
    for (int value : new int[] {0, -1, 16 * 1024 * 1024 + 1}) {
      contextRunner
          .withPropertyValues("trader.news.api.max-request-bytes=" + value)
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(NewsApiProperties.class)
  static class PropertiesConfiguration {}
}
