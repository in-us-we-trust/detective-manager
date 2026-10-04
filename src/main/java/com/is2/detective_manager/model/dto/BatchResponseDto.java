package com.is2.detective_manager.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BatchResponseDto {

  private int deletedOrphanEmbeddings;
  private int orphanEmbeddingsProcessed;
  private int unknownPersonsCreated;
}
