package com.newsswing.core.inference.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InferenceJobPolicyTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(PolicyConfiguration.class);

  @Test
  void defaultsToThreeTotalAssignmentsAndFakeBackend() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(InferenceJobPolicy.class))
              .isEqualTo(new InferenceJobPolicy(3, "fake"));
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"fake", "llama.cpp", "ollama"})
  void acceptsOnlyApprovedLocalBackendNames(String backend) {
    runner
        .withPropertyValues(
            "trader.inference.jobs.expected-backend=" + backend,
            "trader.inference.jobs.max-assignments=1")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(InferenceJobPolicy.class))
                  .isEqualTo(new InferenceJobPolicy(1, backend));
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "2147483648", "not-a-number"})
  void rejectsInvalidAssignmentLimitsAtBinding(String value) {
    runner
        .withPropertyValues("trader.inference.jobs.max-assignments=" + value)
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "openai", "FAKE", "unknown"})
  void rejectsUnsupportedBackendAtBinding(String backend) {
    runner
        .withPropertyValues("trader.inference.jobs.expected-backend=" + backend)
        .run(context -> assertThat(context).hasFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(InferenceJobPolicy.class)
  static class PolicyConfiguration {}
}
