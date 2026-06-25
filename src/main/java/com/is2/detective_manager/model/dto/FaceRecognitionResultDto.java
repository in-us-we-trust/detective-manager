package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaceRecognitionResultDto {

  private Boolean known;
  private Double threshold;
  private Double distance;

  @JsonProperty("distance_metric")
  private String distanceMetric;

  @JsonProperty("closest_match")
  private FaceRecognitionClosestMatchDto closestMatch;

  @JsonProperty("model_name")
  private String modelName;

  @JsonProperty("detector_backend")
  private String detectorBackend;
}
