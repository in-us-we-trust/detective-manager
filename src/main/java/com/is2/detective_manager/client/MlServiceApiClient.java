package com.is2.detective_manager.client;

import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
import com.is2.detective_manager.model.dto.MlAnalysisRequestDto;
import com.is2.detective_manager.model.dto.MlFaceEmbeddingRequestDto;

public interface MlServiceApiClient {

  boolean analyzeIntruder(MlAnalysisRequestDto request);

  GeneratedFaceEmbeddingDto generateFaceEmbedding(MlFaceEmbeddingRequestDto request);
}
