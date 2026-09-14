package com.is2.detective_manager.orchestrator.evaluator;

/** ¿El análisis sigue en curso o ya concluyó? */
public enum AssessmentStatus {
  EVALUATING, // todavía se están corriendo steps
  CONCLUDED // se llegó a un veredicto final (peligroso / seguro / fin del pipeline)
}
