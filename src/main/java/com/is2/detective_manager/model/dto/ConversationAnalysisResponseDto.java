package com.is2.detective_manager.model.dto;

import java.util.List;
import lombok.Data;

/** Respuesta del ML de conversación: /api/ml-service/v1/analysis/conversation */
@Data
public class ConversationAnalysisResponseDto {

  private String transcript;
  private List<EvidenceDto> evidences;

  @Data
  public static class EvidenceDto {
    private String type;
    private Double score;
  }
}
