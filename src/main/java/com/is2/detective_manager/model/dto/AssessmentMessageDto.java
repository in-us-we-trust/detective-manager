package com.is2.detective_manager.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Mensaje que se publica a la cola de assessments tras cada step. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AssessmentMessageDto {

  private String trackId;
  private String step;      // qué step lo produjo
  private String status;    // EVALUATING / CONCLUDED
  private String severity;  // NORMAL / LLAMATIVO / SOSPECHOSO / PELIGROSO
  private String message;   // texto legible
  private String verdict;   // CONTINUE / STOP_DANGER / STOP_SAFE
}
