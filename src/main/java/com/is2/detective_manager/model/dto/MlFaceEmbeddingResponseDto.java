package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlFaceEmbeddingResponseDto {

  private DataDto data;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DataDto {

    private List<Double> embedding;

    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("detector_backend")
    private String detectorBackend;
  }
}
