package com.newsswing.core.inference.jobs;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InferenceJobLifecyclePropertiesTest {
  final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsDefaults() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx.getBean(InferenceJobLifecycleProperties.class))
              .isEqualTo(new InferenceJobLifecycleProperties(Duration.ofSeconds(60), 100));
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"0s", "-1s", "PT0.000001S", "PT9223372036854775807S", "garbage"})
  void rejectsInvalidDuration(String value) {
    runner
        .withPropertyValues("trader.inference.jobs.lease-duration=" + value)
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "1001", "2147483648", "bad"})
  void rejectsInvalidBatch(String value) {
    runner
        .withPropertyValues("trader.inference.jobs.recovery-batch-size=" + value)
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 1000})
  void acceptsBatchBoundaries(int size) {
    runner
        .withPropertyValues(
            "trader.inference.jobs.recovery-batch-size=" + size,
            "trader.inference.jobs.lease-duration=1ms")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getBean(InferenceJobLifecycleProperties.class))
                  .isEqualTo(new InferenceJobLifecycleProperties(Duration.ofMillis(1), size));
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(InferenceJobLifecycleProperties.class)
  static class PropertiesConfiguration {}
}
