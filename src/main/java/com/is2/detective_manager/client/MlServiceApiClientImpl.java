package com.is2.detective_manager.client;

import com.is2.detective_manager.model.dto.MlAnalysisRequestDto;
import com.is2.detective_manager.model.dto.MlAnalysisResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

@Slf4j
public class MlServiceApiClientImpl implements MlServiceApiClient {

  private final RestTemplate restTemplate;
  private final String baseUrl;

  public MlServiceApiClientImpl(RestTemplate restTemplate, String baseUrl) {
    this.restTemplate = restTemplate;
    this.baseUrl = baseUrl;
  }

  @Override
  public boolean analyzeIntruder(MlAnalysisRequestDto request) {
    String url = baseUrl + "/api/ml-service/v1/analysis/intruder";
    log.info("Calling ML service at: {}", url);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<MlAnalysisRequestDto> entity = new HttpEntity<>(request, headers);

    MlAnalysisResponseDto response =
        restTemplate.postForObject(url, entity, MlAnalysisResponseDto.class);

    return response != null
        && response.getData() != null
        && response.getData().isIntruder();
  }
}
