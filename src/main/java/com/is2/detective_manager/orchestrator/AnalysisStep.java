package com.is2.detective_manager.orchestrator;

import com.is2.detective_manager.model.entity.ChunkType;
import java.util.Set;

/**
 * Un paso del pipeline de análisis. El orden lo define el AnalysisPipeline,
 * no el step. Cada step declara qué input necesita; si ese input no llegó, se saltea.
 */
public interface AnalysisStep {

  /** ¿Este step puede correr con los inputs disponibles? (ej: necesito IMAGE y llegó IMAGE) */
  boolean supports(Set<ChunkType> availableInputs);

  /** Ejecuta el análisis y devuelve su aporte (data para acumular). */
  StepResult execute(StepContext context);
}
