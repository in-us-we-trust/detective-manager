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
  ChunkType mediaType; // tipo del chunk: IMAGE / AUDIO / VIDEO
  byte[] payload; // contenido binario crudo (imagen/audio/video)
  Map<String, Object> accumulated; // data de steps anteriores
}
