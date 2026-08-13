package com.is2.detective_manager.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Un chunk de un evento (por ahora, una imagen) enviado por el timbre.")
public class EventChunkRequestDto {

  @NotBlank(message = "trackId is required")
  @Schema(
      description = "Identificador de la visita, generado por el timbre en su primer envío.",
      example = "b3f1c2a4-...",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private String trackId;

  @NotBlank(message = "deviceId is required")
  @Schema(
      description = "Identificador del timbre que envía el chunk.",
      example = "a1b2c3d4-...",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private String deviceId;

  @NotBlank(message = "image is required")
  @Schema(
      description = "Imagen en base64 asociada al evento.",
      example = "/9j/4AAQSkZJRgABAQAAAQABAAD...",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private String image;
}
