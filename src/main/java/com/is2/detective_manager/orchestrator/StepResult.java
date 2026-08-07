package com.is2.detective_manager.orchestrator;

import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/** Lo que devuelve un step: nuevo estado, data para mergear, señales detectadas, y si hay que notificar. */
@Value
@Builder
public class StepResult {

  TrackStatus nextStatus;
  Map<String, Object> data;
  boolean shouldNotify;

  /** Señales semánticas que este step detectó (ej. UNKNOWN_PERSON). El evaluador las matchea contra reglas. */
  @Singular List<Signal> signals;
}
