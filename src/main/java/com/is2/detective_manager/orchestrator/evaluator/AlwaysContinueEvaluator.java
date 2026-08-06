package com.is2.detective_manager.orchestrator.evaluator;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Evaluador dummy: nunca corta, recorre el pipeline entero.
 * Placeholder hasta definir el criterio real (umbral/mapping).
 */
@Slf4j
@Component
public class AlwaysContinueEvaluator implements DangerEvaluator {

  @Override
  public Verdict evaluate(Map<String, Object> accumulatedData) {
    log.info("[dummy] Evaluando peligro con data acumulada={} -> CONTINUE", accumulatedData);
    return Verdict.CONTINUE;
  }
}
