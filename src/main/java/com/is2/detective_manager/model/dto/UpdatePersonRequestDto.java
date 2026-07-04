package com.is2.detective_manager.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Request body used to register a new known person with face images.")
public class UpdatePersonRequestDto {
  @Schema(
      description =
          "Role of the known person. This field is optional and can be used to specify the person's role or relationship.",
      example = "Cuidador",
      requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  private String role;

  @Schema(
      description = "Name of the known person.",
      example = "Juan Perez",
      requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  private String name;
}
