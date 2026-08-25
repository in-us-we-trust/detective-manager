package com.is2.detective_manager.orchestrator.step.objectrecognition;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.ObjectDetectionResponseDto;
import java.util.Base64;
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
 * Real: llama al ML /api/ml-service/v1/analysis/object-detection con la imagen
 * en base64 (JSON). Devuelve los objetos detectados (label + confidence) y counts.
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

    if (response == null || response.getData() == null) {
      log.warn("El ML de detección de objetos devolvió una respuesta vacía");
      return new ObjectDetectionResult(List.of(), Map.of());
    }

    ObjectDetectionResponseDto.Data data = response.getData();

    List<DetectedObject> objects =
        data.getObjects() == null
            ? List.of()
            : data.getObjects().stream()
                .map(o -> new DetectedObject(o.getLabel(), o.getConfidence()))
                .toList();

    Map<String, Integer> counts = data.getCounts() == null ? Map.of() : data.getCounts();

    log.info("Objetos detectados: {}, counts: {}", objects.size(), counts);
    return new ObjectDetectionResult(objects, counts);
  }
}
