package com.newsswing.core.inference.jobs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InferenceJobLifecycleProperties.class)
class InferenceJobLifecycleConfiguration {
  @Bean
  InferenceResultContractValidator inferenceResultContractValidator() throws IOException {
    try (var input =
        new ClassPathResource("contracts/v1/inference-result.schema.json").getInputStream()) {
      return new InferenceResultContractValidator(
          new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Bean
  LifecycleTimeSource lifecycleTimeSource() {
    return new PostgresLifecycleTimeSource();
  }
}
