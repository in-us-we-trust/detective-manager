package com.is2.detective_manager.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** Resumen de un track (visita) para el listado por dispositivo. */
@Data
@Builder
@AllArgsConstructor
@Schema(description = "Resumen de una visita analizada.")
public class TrackResponseDto {

  private UUID trackId;
  private UUID deviceId;
  private String status;             // CREATED / CLOSED / ...
  private String currentStep;
  private String accumulatedSignals; // CSV
  private String finalVerdict;
  private String finalSeverity;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
