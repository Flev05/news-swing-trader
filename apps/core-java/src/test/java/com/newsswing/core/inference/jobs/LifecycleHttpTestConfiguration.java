package com.newsswing.core.inference.jobs;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Explicitly imported only by the HTTP lifecycle compatibility test. */
@TestConfiguration(proxyBeanMethods = false)
public class LifecycleHttpTestConfiguration {
  @Bean
  @Primary
  LifecycleTimeSource httpLifecycleTime() {
    return connection -> Instant.parse("2026-01-15T14:30:30Z");
  }
}
