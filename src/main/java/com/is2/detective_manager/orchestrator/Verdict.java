package com.is2.detective_manager.orchestrator;

/** Decisión del orquestador tras cada step. */
public enum Verdict {
  CONTINUE,    // seguir con el próximo step
  STOP_DANGER, // se concluyó que el evento es peligroso: cortar
  STOP_SAFE    // se concluyó que el evento es seguro: cortar
}
