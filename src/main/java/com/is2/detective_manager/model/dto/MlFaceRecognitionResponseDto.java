package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlFaceRecognitionResponseDto {

  private FaceRecognitionResultDto data;
}
