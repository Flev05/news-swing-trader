package com.newsswing.core.bootstrap;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Rejects configuration that could expose the local HTTP server beyond loopback. */
public final class LoopbackOnlyEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  private static final String LOOPBACK_ADDRESS = "127.0.0.1";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String serverAddress = environment.getProperty("server.address");
    if (!LOOPBACK_ADDRESS.equals(serverAddress)) {
      throw new IllegalStateException("server.address must be exactly 127.0.0.1");
    }

    if (environment.containsProperty("management.server.address")) {
      throw new IllegalStateException("management.server.address is not permitted in M2B2A");
    }

    String managementPort = environment.getProperty("management.server.port");
    if (managementPort != null && !"-1".equals(managementPort)) {
      throw new IllegalStateException("management.server.port must be absent or -1 in M2B2A");
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
