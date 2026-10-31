package com.is2.detective_manager.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
@Schema(description = "Resultado del análisis del chunk recibido.")
public class EventChunkResponseDto {

  @Schema(description = "El trackId recibido.")
  private String trackId;

  @Schema(description = "Tipo de chunk recibido.", example = "IMAGE")
  private String chunkType;

  @Schema(description = "Veredicto final del pipeline.", example = "DANGER")
  private String finalVerdict;

  @Schema(description = "Severity final alcanzada.", example = "PELIGROSO")
  private String finalSeverity;

  @Schema(description = "Mensaje de la conclusión.", example = "La persona está armada")
  private String message;

  @Schema(
      description =
          "Información adicional flexible del chunk. En IMAGE puede incluir personName. Para varios rostros incluye faces (box en píxeles), faceCount, knownFaceCount, unknownFaceCount y personNames. Las identidades se informan únicamente cuando known=true; faceIndex sólo identifica el rostro dentro de esa respuesta.",
      example = "{\"personName\":\"Juan Pérez\"}")
  private Map<String, Object> additionalInformation;
}
