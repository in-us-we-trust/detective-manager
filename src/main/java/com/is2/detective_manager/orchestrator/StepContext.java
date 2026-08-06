package com.is2.detective_manager.orchestrator;

import com.is2.detective_manager.model.entity.ChunkType;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/** Lo que recibe un step para ejecutarse. Solo-lectura: el step no toca entities. */
@Value
@Builder
public class StepContext {

  String trackId;
  ChunkType chunkType;
  String payload; // imagen/audio en base64
  Map<String, Object> accumulated; // data de steps anteriores (tags, transcript, etc.)
}
