package com.newsswing.core.inference.jobs;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("trader.inference.jobs")
record InferenceJobLifecycleProperties(Duration leaseDuration, int recoveryBatchSize) {
  InferenceJobLifecycleProperties {
    if (leaseDuration == null
        || leaseDuration.isNegative()
        || leaseDuration.isZero()
        || leaseDuration.getNano() % 1_000_000 != 0) {
      throw new IllegalArgumentException("lease-duration must be positive whole milliseconds");
    }
    try {
      leaseDuration.toMillis();
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("lease-duration overflows milliseconds", overflow);
    }
    if (recoveryBatchSize < 1 || recoveryBatchSize > 1000) {
      throw new IllegalArgumentException("recovery-batch-size must be between 1 and 1000");
    }
  }
}
