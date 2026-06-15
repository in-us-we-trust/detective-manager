package com.is2.detective_manager.client;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
import com.is2.detective_manager.model.dto.MlAnalysisRequestDto;
import com.is2.detective_manager.model.dto.MlAnalysisResponseDto;
import com.is2.detective_manager.model.dto.MlFaceEmbeddingRequestDto;
import com.is2.detective_manager.model.dto.MlFaceEmbeddingResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
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

    return response != null && response.getData() != null && response.getData().isIntruder();
  }

  @Override
  public GeneratedFaceEmbeddingDto generateFaceEmbedding(MlFaceEmbeddingRequestDto request) {
    String url = baseUrl + "/api/ml-service/v1/analysis/face-embedding";
    log.info("Requesting face embedding from ML service at: {}", url);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<MlFaceEmbeddingRequestDto> entity = new HttpEntity<>(request, headers);

    MlFaceEmbeddingResponseDto response;
    try {
      response = restTemplate.postForObject(url, entity, MlFaceEmbeddingResponseDto.class);
    } catch (RestClientException exception) {
      log.error("Failed to generate face embedding through ML service", exception);
      throw new DetectiveException(ExceptionEnum.ML_SERVICE_ERROR);
    }

    if (response == null || response.getData() == null) {
      return null;
    }

    return new GeneratedFaceEmbeddingDto(
        response.getData().getEmbedding(),
        response.getData().getModelName(),
        response.getData().getDetectorBackend());
  }
}
