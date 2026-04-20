package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlAnalysisResponseDto {

  private DataDto data;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DataDto {

    @JsonProperty("is_intruder")
    private boolean intruder;
  }
}
