package com.newsswing.core.inference.jobs;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InferenceJobLifecycleProperties.class)
class InferenceJobLifecycleConfiguration {
  @Bean
  LifecycleTimeSource lifecycleTimeSource() {
    return new PostgresLifecycleTimeSource();
  }
}
