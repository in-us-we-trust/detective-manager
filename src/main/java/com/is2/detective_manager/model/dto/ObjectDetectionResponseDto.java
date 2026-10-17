package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Respuesta del ML de detección de objetos: /api/ml-service/v1/analysis/object-detection La
 * respuesta viene envuelta en un nodo "data". Solo nos interesan objects y counts.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ObjectDetectionResponseDto {

  private Data data;

  @lombok.Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Data {
    private List<DetectedObjectDto> objects;
    private Map<String, Integer> counts;
  }

  @lombok.Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DetectedObjectDto {
    private String label;
    private Double confidence;
  }
}
