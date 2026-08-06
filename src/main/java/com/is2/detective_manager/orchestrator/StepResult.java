package com.is2.detective_manager.orchestrator;

import com.is2.detective_manager.model.entity.TrackStatus;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/** Lo que devuelve un step: nuevo estado, data para mergear, y si hay que notificar. */
@Value
@Builder
public class StepResult {

  TrackStatus nextStatus;
  Map<String, Object> data;
  boolean shouldNotify;
}
