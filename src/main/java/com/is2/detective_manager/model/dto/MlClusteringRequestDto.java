package com.is2.detective_manager.model.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MlClusteringRequestDto {

  private List<FaceEmbeddingResponseDto> embeddings;
}
