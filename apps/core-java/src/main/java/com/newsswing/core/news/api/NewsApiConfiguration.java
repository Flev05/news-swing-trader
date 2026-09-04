package com.newsswing.core.news.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NewsApiProperties.class)
class NewsApiConfiguration {}
