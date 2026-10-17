package com.is2.detective_manager.orchestrator.step.objectrecognition;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.ObjectDetectionResponseDto;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Real: llama al ML /api/ml-service/v1/analysis/object-detection con la imagen en base64 (JSON).
 * Devuelve los objetos detectados (label + confidence) y counts.
 */
@Slf4j
@Component
@Profile("!mock-objects")
public class RealObjectRecognizer implements ObjectRecognizer {

  private final RestTemplate restTemplate;
  private final String baseUrl;

  public RealObjectRecognizer(
      RestTemplate restTemplate, @Value("${ml.service.base-url}") String baseUrl) {
    this.restTemplate = restTemplate;
    this.baseUrl = baseUrl;
  }

  @Override
  public ObjectDetectionResult detectObjects(byte[] image) {
    String url = baseUrl + "/api/ml-service/v1/analysis/object-detection";
    log.info("Llamando al ML de detección de objetos en: {}", url);

    // Cuerpo JSON: { "image": "<base64>" }
    String base64Image = Base64.getEncoder().encodeToString(image);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<Map<String, String>> entity =
        new HttpEntity<>(Map.of("image", base64Image), headers);

    ObjectDetectionResponseDto response;
    try {
      response = restTemplate.postForObject(url, entity, ObjectDetectionResponseDto.class);
    } catch (RestClientException exception) {
      log.error("Falló la llamada al ML de detección de objetos", exception);
      throw new DetectiveException(ExceptionEnum.ML_SERVICE_ERROR);
    }

    if (response == null
        || response.getData() == null
        || (response.getData().getObjects() == null && response.getData().getCounts() == null)) {
      throw new DetectiveException(
          ExceptionEnum.ML_SERVICE_ERROR,
          "El ML de detección de objetos devolvió una respuesta incompleta.");
    }

    ObjectDetectionResponseDto.Data data = response.getData();

    List<DetectedObject> objects = new ArrayList<>();
    AnalysisQuality quality =
        data.getObjects() == null || data.getCounts() == null
            ? AnalysisQuality.INSUFFICIENT
            : AnalysisQuality.VALID;
    List<ObjectDetectionResponseDto.DetectedObjectDto> receivedObjects =
        data.getObjects() != null ? data.getObjects() : List.of();
    for (ObjectDetectionResponseDto.DetectedObjectDto object : receivedObjects) {
      if (object == null
          || object.getLabel() == null
          || object.getLabel().isBlank()
          || object.getConfidence() == null
          || !Double.isFinite(object.getConfidence())
          || object.getConfidence() < 0
          || object.getConfidence() > 1) {
        quality = AnalysisQuality.INSUFFICIENT;
        continue;
      }
      objects.add(new DetectedObject(object.getLabel(), object.getConfidence()));
    }

    Map<String, Integer> counts = new HashMap<>();
    Map<String, Integer> receivedCounts = data.getCounts() != null ? data.getCounts() : Map.of();
    for (Map.Entry<String, Integer> entry : receivedCounts.entrySet()) {
      if (entry.getKey() == null
          || entry.getKey().isBlank()
          || entry.getValue() == null
          || entry.getValue() < 0) {
        quality = AnalysisQuality.INSUFFICIENT;
        continue;
      }
      counts.put(entry.getKey(), entry.getValue());
    }

    log.info("Objetos detectados: {}, counts: {}", objects.size(), counts);
    return new ObjectDetectionResult(List.copyOf(objects), Map.copyOf(counts), quality);
  }
}
