package com.is2.detective_manager.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request body used by a device to report a detection event.")
public class ReportEventRequestDto {

  @NotBlank(message = "image is required")
  @Schema(
      description = "Base64-encoded image associated with the reported event.",
      example = "/9j/4AAQSkZJRgABAQAAAQABAAD...",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private String image;

  @Schema(description = "Type of the reported event.", example = "PERSON_DETECTED")
  private String type;
}
