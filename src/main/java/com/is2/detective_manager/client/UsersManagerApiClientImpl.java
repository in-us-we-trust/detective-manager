package com.is2.detective_manager.client;

import com.is2.detective_manager.model.dto.NotifyIntruderRequestDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

@Slf4j
public class UsersManagerApiClientImpl implements UsersManagerApiClient {

  private final RestTemplate restTemplate;
  private final String baseUrl;

  public UsersManagerApiClientImpl(RestTemplate restTemplate, String baseUrl) {
    this.restTemplate = restTemplate;
    this.baseUrl = baseUrl;
  }

  @Override
  public void notifyIntruder(NotifyIntruderRequestDto request) {
    String url = baseUrl + "/api/users-manager/notifications/intruder";
    log.info("Notifying users manager of intruder for device: {}", request.getDeviceId());

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<NotifyIntruderRequestDto> entity = new HttpEntity<>(request, headers);

    restTemplate.postForObject(url, entity, Void.class);
  }
}
