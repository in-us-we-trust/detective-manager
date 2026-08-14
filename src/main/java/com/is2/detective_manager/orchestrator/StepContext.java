package com.is2.detective_manager.orchestrator;

import com.is2.detective_manager.model.entity.ChunkType;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** Lo que recibe un step para ejecutarse. Solo-lectura: el step no toca entities. */
@Value
@Builder
public class StepContext {

  String trackId;
  UUID deviceId; // el device del que vino el chunk (para buscar embeddings, etc.)
  ChunkType mediaType;
  byte[] payload; // contenido binario crudo
  Map<String, Object> accumulated;
}
