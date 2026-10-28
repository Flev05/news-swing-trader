package com.newsswing.core.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.newsswing.core.CoreApplication;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class LoopbackOnlyServerInvariantTest {

  private final LoopbackOnlyEnvironmentPostProcessor processor =
      new LoopbackOnlyEnvironmentPostProcessor();

  @Test
  void permitsOnlyTheExactIpv4LoopbackAddressAndAnyServerPort() {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "test", Map.of("server.address", "127.0.0.1", "server.port", "0")));

    processor.postProcessEnvironment(environment, new SpringApplication(CoreApplication.class));
  }

  @Test
  void rejectsDirectAndEnvironmentStyleExternalAddressOverrides() {
    var direct = new StandardEnvironment();
    direct
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "test", Map.of("server.address", "0.0.0.0")));
    assertThatThrownBy(
            () ->
                processor.postProcessEnvironment(
                    direct, new SpringApplication(CoreApplication.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("server.address must be exactly 127.0.0.1");

    var environmentStyle = new StandardEnvironment();
    environmentStyle
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "test-systemEnvironment", Map.of("SERVER_ADDRESS", "0.0.0.0")));
    assertThatThrownBy(
            () ->
                processor.postProcessEnvironment(
                    environmentStyle, new SpringApplication(CoreApplication.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("server.address must be exactly 127.0.0.1");
  }

  @Test
  void rejectsManagementListenerConfiguration() {
    for (String property :
        new String[] {
          "management.server.address=127.0.0.1", "management.server.port=0",
          "management.server.port=8080", "management.server.port=9090"
        }) {
      var environment = new StandardEnvironment();
      String[] parts = property.split("=", 2);
      environment
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "test", Map.of("server.address", "127.0.0.1", parts[0], parts[1])));
      assertThatThrownBy(
              () ->
                  processor.postProcessEnvironment(
                      environment, new SpringApplication(CoreApplication.class)))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void rejectsEnvironmentStyleManagementListenersAndAllowsExplicitDisable() {
    for (var override :
        Map.of("MANAGEMENT_SERVER_ADDRESS", "127.0.0.1", "MANAGEMENT_SERVER_PORT", "0")
            .entrySet()) {
      var environment = new StandardEnvironment();
      environment
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "test", Map.of("server.address", "127.0.0.1")));
      environment
          .getPropertySources()
          .addFirst(
              new SystemEnvironmentPropertySource(
                  "test-systemEnvironment", Map.of(override.getKey(), override.getValue())));
      assertThatThrownBy(
              () ->
                  processor.postProcessEnvironment(
                      environment, new SpringApplication(CoreApplication.class)))
          .isInstanceOf(IllegalStateException.class);
    }
    var disabled = new StandardEnvironment();
    disabled
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "test", Map.of("server.address", "127.0.0.1", "management.server.port", "-1")));
    processor.postProcessEnvironment(disabled, new SpringApplication(CoreApplication.class));
  }

  @Test
  void commandLineManagementListenerFailsBeforeAnyWebServerCanStart() {
    var started = new AtomicBoolean();
    var application = new SpringApplication(CoreApplication.class);
    application.addListeners(
        event -> {
          if (event instanceof WebServerInitializedEvent) {
            started.set(true);
          }
        });
    assertThatThrownBy(() -> application.run("--management.server.port=0", "--server.port=0"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(started).isFalse();
  }

  @Test
  void commandLineExternalOverrideFailsBeforeAnyWebServerCanStart() {
    var started = new AtomicBoolean();
    var application = new SpringApplication(CoreApplication.class);
    application.addListeners(
        event -> {
          if (event instanceof WebServerInitializedEvent) {
            started.set(true);
          }
        });

    assertThatThrownBy(() -> application.run("--server.address=0.0.0.0", "--server.port=0"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("server.address must be exactly 127.0.0.1");
    assertThat(started).isFalse();
  }

  @Test
  void springApplicationJsonExternalOverrideFailsBeforeAnyWebServerCanStart() {
    var application = new SpringApplication(CoreApplication.class);

    assertThatThrownBy(
            () ->
                application.run(
                    "--spring.application.json={\"server\":{\"address\":\"0.0.0.0\"}}",
                    "--server.port=0"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("server.address must be exactly 127.0.0.1");
  }
}
