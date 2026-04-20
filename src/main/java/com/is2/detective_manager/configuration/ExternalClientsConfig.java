package com.is2.detective_manager.configuration;

import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.client.MlServiceApiClientImpl;
import com.is2.detective_manager.client.UsersManagerApiClient;
import com.is2.detective_manager.client.UsersManagerApiClientImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
@Slf4j
public class ExternalClientsConfig {

  @Bean
  public MlServiceApiClient mlServiceApiClient(
      RestTemplate restTemplate, @Value("${ml.service.base-url}") String baseUrl) {
    log.info("Configuring ML Service API client with base URL: {}", baseUrl);
    return new MlServiceApiClientImpl(restTemplate, baseUrl);
  }

  @Bean
  public UsersManagerApiClient usersManagerApiClient(
      RestTemplate restTemplate, @Value("${users-manager.base-url}") String baseUrl) {
    log.info("Configuring Users Manager API client with base URL: {}", baseUrl);
    return new UsersManagerApiClientImpl(restTemplate, baseUrl);
  }
}
