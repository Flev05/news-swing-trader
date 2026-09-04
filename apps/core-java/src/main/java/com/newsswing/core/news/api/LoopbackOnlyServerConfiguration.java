package com.newsswing.core.news.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Final server-factory guard for the loopback-only startup invariant. */
@Configuration(proxyBeanMethods = false)
class LoopbackOnlyServerConfiguration {

  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> forceLoopbackAddress() {
    return factory -> {
      try {
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
      } catch (UnknownHostException exception) {
        throw new IllegalStateException(
            "Unable to resolve the required loopback address", exception);
      }
    };
  }
}
