package com.is2.detective_manager.orchestrator.evaluator;

import java.util.List;
import java.util.Map;

/**
 * Severidad individual de cada señal (para rankear, ej. elegir la imagen más
 * peligrosa de un track). Es una aproximación por señal suelta, distinta de la
 * evaluación por reglas (que mira combinaciones). Se usa solo para ordenar.
 */
public final class SignalSeverity {

  private SignalSeverity() {}

  private static final Map<Signal, Severity> SEVERITY_BY_SIGNAL =
      Map.ofEntries(
          Map.entry(Signal.WEAPON_DETECTED, Severity.PELIGROSO),
          Map.entry(Signal.THREAT, Severity.PELIGROSO),
          Map.entry(Signal.COVERED_FACE, Severity.SOSPECHOSO),
          Map.entry(Signal.MULTIPLE_PEOPLE, Severity.SOSPECHOSO),
          Map.entry(Signal.AUTHORITY, Severity.SOSPECHOSO),
          Map.entry(Signal.URGENCY, Severity.SOSPECHOSO),
          Map.entry(Signal.PRESSURE, Severity.SOSPECHOSO),
          Map.entry(Signal.VERIFICATION_AVOIDANCE, Severity.SOSPECHOSO),
          Map.entry(Signal.CODE_REQUEST, Severity.SOSPECHOSO),
          Map.entry(Signal.UNKNOWN_PERSON, Severity.LLAMATIVO),
          Map.entry(Signal.PERSON, Severity.NORMAL),
          Map.entry(Signal.KNOWN_PERSON, Severity.NORMAL),
          Map.entry(Signal.NO_THREAT_OBJECT, Severity.NORMAL));

  /** Severity de una señal suelta (NORMAL si no está mapeada). */
  public static Severity of(Signal signal) {
    return SEVERITY_BY_SIGNAL.getOrDefault(signal, Severity.NORMAL);
  }

  /** La mayor severity entre un conjunto de señales (NORMAL si está vacío). */
  public static Severity maxOf(List<Signal> signals) {
    return signals.stream()
        .map(SignalSeverity::of)
        .max((a, b) -> Integer.compare(a.getLevel(), b.getLevel()))
        .orElse(Severity.NORMAL);
  }
}
