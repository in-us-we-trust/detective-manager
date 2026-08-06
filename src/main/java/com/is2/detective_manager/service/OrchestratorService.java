package com.is2.detective_manager.service;

import com.is2.detective_manager.model.dto.EventChunkRequestDto;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;

public interface OrchestratorService {

  EventChunkResponseDto handleEvent(EventChunkRequestDto request);
}
