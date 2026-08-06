package com.is2.detective_manager.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
@Schema(description = "Confirmación de que el chunk fue recibido.")
public class EventChunkResponseDto {

  @Schema(description = "El trackId recibido.")
  private String trackId;

  @Schema(description = "Tipo de chunk recibido.", example = "IMAGE")
  private String chunkType;

  @Schema(description = "Tamaño del payload recibido, en bytes.")
  private int payloadBytes;

  @Schema(description = "Mensaje de estado.")
  private String message;
}
