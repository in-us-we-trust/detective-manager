package com.is2.detective_manager.client;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.ClusterDto;
import com.is2.detective_manager.model.dto.FaceRecognitionResultDto;
import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
import com.is2.detective_manager.model.dto.MlAnalysisRequestDto;
import com.is2.detective_manager.model.dto.MlAnalysisResponseDto;
import com.is2.detective_manager.model.dto.MlClusteringRequestDto;
import com.is2.detective_manager.model.dto.MlClusteringResponseDto;
import com.is2.detective_manager.model.dto.MlFaceEmbeddingRequestDto;
import com.is2.detective_manager.model.dto.MlFaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.MlFaceRecognitionRequestDto;
import com.is2.detective_manager.model.dto.MlFaceRecognitionResponseDto;
import java.util.Collections;
import java.util.List;
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

  @Override
  public FaceRecognitionResultDto recognizeFace(MlFaceRecognitionRequestDto request) {
    String url = baseUrl + "/api/ml-service/v1/analysis/face-recognition";
    log.info("Requesting face recognition from ML service at: {}", url);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<MlFaceRecognitionRequestDto> entity = new HttpEntity<>(request, headers);

    MlFaceRecognitionResponseDto response;
    try {
      response = restTemplate.postForObject(url, entity, MlFaceRecognitionResponseDto.class);
    } catch (RestClientException exception) {
      log.error("Failed to recognize face through ML service", exception);
      throw new DetectiveException(ExceptionEnum.ML_SERVICE_ERROR);
    }

    if (response == null || response.getData() == null || response.getData().getKnown() == null) {
      throw new DetectiveException(
          ExceptionEnum.ML_SERVICE_ERROR,
          "The ML service returned an invalid face recognition response.");
    }

    return response.getData();
  }

  @Override
  public List<ClusterDto> clusterEmbeddings(MlClusteringRequestDto request) {
    String url = baseUrl + "/api/ml-service/v1/analysis/face-embedding/clustering";
    log.info("Requesting face embedding clustering from ML service at: {}", url);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<MlClusteringRequestDto> entity = new HttpEntity<>(request, headers);

    MlClusteringResponseDto response;
    try {
      response = restTemplate.postForObject(url, entity, MlClusteringResponseDto.class);
    } catch (RestClientException exception) {
      log.error("Failed to cluster embeddings through ML service", exception);
      throw new DetectiveException(ExceptionEnum.ML_SERVICE_ERROR);
    }

    if (response == null || response.getData() == null) {
      log.warn("ML clustering service returned empty response");
      return Collections.emptyList();
    }

    return response.getData();
  }
}
