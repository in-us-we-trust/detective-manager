package com.is2.detective_manager.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReportEventRequestDto {

  @NotBlank(message = "image is required")
  private String image;

  @NotBlank(message = "type is required")
  private String type;
}
