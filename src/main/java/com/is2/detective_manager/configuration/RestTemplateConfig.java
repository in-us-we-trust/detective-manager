package com.is2.detective_manager.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Configuration
public class RestTemplateConfig {

  @Bean
  public RestTemplate restTemplate() {
    RestTemplate restTemplate = new RestTemplate(
        new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()));
    restTemplate.setInterceptors(List.of(loggingInterceptor()));
    return restTemplate;
  }

  private ClientHttpRequestInterceptor loggingInterceptor() {
    return (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
      log.info("[HTTP OUT] {} {} | body: {}", request.getMethod(), request.getURI(),
          new String(body, StandardCharsets.UTF_8));
      ClientHttpResponse response = execution.execute(request, body);
      byte[] responseBody = response.getBody().readAllBytes();
      log.info("[HTTP IN] status: {} | body: {}", response.getStatusCode(),
          new String(responseBody, StandardCharsets.UTF_8));
      return response;
    };
  }
}
