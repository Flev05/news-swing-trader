package com.newsswing.core.inference.jobs;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class WorkerLifecycleTestConfiguration {
  public static final Instant NOW = Instant.parse("2026-01-15T14:30:30.123456Z");

  public static final class ControlledTime implements LifecycleTimeSource {
    private final AtomicReference<Instant> value = new AtomicReference<>(NOW);

    public void set(Instant now) {
      value.set(now);
    }

    public Instant now(java.sql.Connection connection) {
      return value.get();
    }
  }

  @Bean
  @Primary
  ControlledTime workerLifecycleTime() {
    return new ControlledTime();
  }

  @Bean
  @Primary
  Clock workerIngestionClock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }
}
