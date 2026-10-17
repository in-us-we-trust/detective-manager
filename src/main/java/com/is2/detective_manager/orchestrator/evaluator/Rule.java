package com.is2.detective_manager.orchestrator.evaluator;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

/**
 * Una regla del evaluador. Matchea si las señales acumuladas CONTIENEN todas las de 'requires' (sin
 * importar qué otras haya). Las reglas se evalúan en orden y gana la primera que matchea (jerarquía
 * = prioridad).
 */
@Value
@Builder
public class Rule {

  Set<Signal> requires; // señales que deben estar presentes para que la regla aplique
  Severity severity;
  Verdict verdict;
  // Mayor valor = se muestra antes dentro del mismo nivel. No modifica gravedad ni veredicto.
  int messagePriority;
  String message;

  /** ¿Las señales presentes contienen todas las que esta regla requiere? */
  public boolean matches(Set<Signal> present) {
    return present.containsAll(requires);
  }
}
