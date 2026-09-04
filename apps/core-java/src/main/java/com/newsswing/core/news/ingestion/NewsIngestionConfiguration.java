package com.newsswing.core.news.ingestion;

import com.newsswing.core.news.domain.NewsCanonicalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NewsIngestionProperties.class)
class NewsIngestionConfiguration {

  @Bean
  Clock newsIngestionClock() {
    return Clock.systemUTC();
  }

  @Bean
  NewsCanonicalizer newsCanonicalizer() {
    return new NewsCanonicalizer();
  }

  @Bean
  NewsEventContractValidator newsEventContractValidator() throws IOException {
    var resource = new ClassPathResource("contracts/v1/news-event.schema.json");
    try (var input = resource.getInputStream()) {
      return new NewsEventContractValidator(
          new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
