package com.is2.detective_manager.orchestrator.evaluator;

import java.util.List;

/**
 * La lógica que determina si el evento es peligroso, seguro, o si hay que seguir.
 * Recibe las señales acumuladas hasta el momento y produce un Assessment
 * (severity + score + message + verdict). Acá vive el criterio (umbral/mapping).
 */
public interface DangerEvaluator {

  Assessment evaluate(List<Signal> accumulatedSignals);
}
