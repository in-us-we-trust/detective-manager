package com.is2.detective_manager.client;

import com.is2.detective_manager.model.dto.MlAnalysisRequestDto;

public interface MlServiceApiClient {

  boolean analyzeIntruder(MlAnalysisRequestDto request);
}
