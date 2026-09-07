package com.is2.detective_manager.orchestrator.evaluator;

import com.is2.detective_manager.orchestrator.Verdict;
import lombok.Builder;
import lombok.Value;

/**
 * Resultado de una evaluación tras un step: qué tan peligroso se ve el evento
 * hasta ahora, con un mensaje legible. El orquestador produce uno de estos
 * después de cada step y emite solo cuando la severity cambia.
 */
@Value
@Builder
public class Assessment {

  AssessmentStatus status; // EVALUATING mientras hay más steps; CONCLUDED al cerrar
  Severity severity;       // nivel de alarma
  String message;          // texto legible, ej. "No se reconoce a la persona"
  Verdict verdict;         // CONTINUE / DANGER / SAFE
}
