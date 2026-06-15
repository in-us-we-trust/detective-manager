package com.is2.detective_manager.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateFaceEmbeddingRequestDto {

  @NotBlank(message = "personName is required")
  private String personName;

  @NotBlank(message = "image is required")
  private String image;
}
