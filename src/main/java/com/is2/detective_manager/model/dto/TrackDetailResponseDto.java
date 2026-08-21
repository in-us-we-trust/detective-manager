package com.is2.detective_manager.model.dto;

import com.is2.detective_manager.mapper.TrackAppCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * Detalle de un track (visita). Hoy trae los mismos campos reducidos que el
 * listado + la imagen. Es un DTO separado a propósito, para poder crecer el
 * detalle (más campos, assessments, etc.) sin afectar el listado.
 */
@Data
@Builder
@AllArgsConstructor
@Schema(description = "Detalle de una visita analizada.")
public class TrackDetailResponseDto {

  private TrackAppCode code;
  private String title;
  private String finalVerdict;
  private String finalSeverity;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private String imageUrl; // null si el track no tiene imagen
}
