package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FaceEmbeddingResponseDto {

  private Long id;
  private UUID deviceId;
  private Long personId;
  private String personName;
  private List<Double> embedding;
  private String modelName;
  private String detectorBackend;
  private int dimensions;

  @JsonFormat(shape = JsonFormat.Shape.STRING)
  private LocalDateTime createdAt;
}
