package com.is2.detective_manager.orchestrator.evaluator;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.Map;

/**
 * La lógica que determina si el evento es peligroso, seguro, o si hay que seguir.
 * El orquestador la consulta después de CADA step, con toda la data acumulada.
 * Acá va a vivir el umbral/mapping/scoring (TBD). Es la única pieza que cambia
 * cuando se define el criterio real.
 */
public interface DangerEvaluator {

  Verdict evaluate(Map<String, Object> accumulatedData);
}
