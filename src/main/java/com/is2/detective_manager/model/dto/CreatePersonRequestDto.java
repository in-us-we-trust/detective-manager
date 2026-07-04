package com.is2.detective_manager.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "Request body used to register a new known person with face images.")
public class CreatePersonRequestDto {

  @NotBlank(message = "name is required")
  @Schema(
      description = "Name of the known person.",
      example = "Juan Perez",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private String name;

  @NotEmpty(message = "images is required")
  @Schema(
      description =
          "List of base64 encoded images used to generate face embeddings and upload to storage.",
      example = "[\"base64Image1...\", \"base64Image2...\"]",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private List<String> images;

  @Schema(
      description =
          "Role of the known person. This field is optional and can be used to specify the person's role or relationship.",
      example = "Cuidador",
      requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  private String role;
}
