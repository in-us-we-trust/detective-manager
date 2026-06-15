package com.is2.detective_manager.service;

import com.is2.detective_manager.model.dto.CreateFaceEmbeddingRequestDto;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import java.util.List;
import java.util.UUID;

public interface FaceEmbeddingService {

  FaceEmbeddingResponseDto createFaceEmbedding(
      UUID deviceId, CreateFaceEmbeddingRequestDto request);

  List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId);

  List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId, Long personId);
}
