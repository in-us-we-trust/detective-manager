package com.is2.detective_manager.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** Un assessment tal como se expone por HTTP (o, a futuro, por WebSocket). */
@Data
@Builder
@AllArgsConstructor
@Schema(description = "Evaluación parcial del evento tras un step.")
public class AssessmentDto {

  @Schema(description = "Estado del análisis.", example = "EVALUATING")
  private String status;

  @Schema(description = "Nivel de alarma.", example = "LLAMATIVO")
  private String severity;

  @Schema(description = "Mensaje legible.", example = "No se reconoce a la persona")
  private String message;

  @Schema(description = "Decisión del orquestador.", example = "CONTINUE")
  private String verdict;
}
