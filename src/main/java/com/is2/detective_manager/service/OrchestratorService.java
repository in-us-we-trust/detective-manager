package com.is2.detective_manager.service;

import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;

public interface OrchestratorService {

  EventChunkResponseDto handleEvent(
      String trackId, String deviceId, ChunkType mediaType, byte[] payload);
}
