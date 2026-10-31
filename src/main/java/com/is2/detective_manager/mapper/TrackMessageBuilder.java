package com.is2.detective_manager.mapper;

import com.is2.detective_manager.orchestrator.evaluator.DangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.KnownPersonAttention;
import com.is2.detective_manager.orchestrator.evaluator.MixedRecognitionEvidence;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.orchestrator.evaluator.VisitEvidenceRules;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Construye la presentación de un track para la app: un CODE estable (para mapear a íconos/colores)
 * + un TITLE legible. Ambos salen de la MISMA decisión por prioridad, así siempre son coherentes.
 *
 * <p>Un track tiene varias señales a la vez; el code es UNA categoría que resume, elegida por
 * prioridad. Los patrones de evidencias de la visita, los conocidos que requieren atención y el
 * contexto de objetos usan la evaluación para conservar la prioridad de las demás alertas sin
 * cambiar sus codes.
 */
@Component
@RequiredArgsConstructor
public class TrackMessageBuilder {

  private static final Set<Signal> OBJECT_CONTEXT_SIGNALS =
      EnumSet.of(Signal.LADDER_DETECTED, Signal.TOOL_DETECTED, Signal.PACKAGE_DETECTED);

  private final DangerEvaluator dangerEvaluator;

  public TrackDisplay build(String accumulatedSignalsCsv) {
    Set<Signal> signals = parse(accumulatedSignalsCsv);

    if (signals.contains(Signal.WEAPON_DETECTED)) {
      return new TrackDisplay(TrackAppCode.WEAPON, "Persona armada en la puerta");
    }
    if (signals.contains(Signal.THREAT)) {
      return new TrackDisplay(TrackAppCode.THREAT, "Amenaza detectada en la puerta");
    }
    if (KnownPersonAttention.appliesTo(signals)
        || MixedRecognitionEvidence.appliesTo(signals)
        || signals.contains(Signal.PROLONGED_VISIT_PRESENCE)
        || signals.contains(Signal.COVERED_FACE)
        || VisitEvidenceRules.matchesAny(signals)
        || !Collections.disjoint(signals, OBJECT_CONTEXT_SIGNALS)) {
      TrackAppCode code =
          signals.contains(Signal.KNOWN_PERSON)
              ? TrackAppCode.KNOWN_PERSON
              : signals.contains(Signal.UNKNOWN_PERSON)
                  ? TrackAppCode.UNKNOWN_PERSON
                  : TrackAppCode.ACTIVITY;
      return new TrackDisplay(code, dangerEvaluator.evaluate(List.copyOf(signals)).getMessage());
    }
    if (signals.contains(Signal.KNOWN_PERSON)) {
      return new TrackDisplay(TrackAppCode.KNOWN_PERSON, "Persona reconocida en la puerta");
    }
    if (signals.contains(Signal.UNKNOWN_PERSON)) {
      return new TrackDisplay(TrackAppCode.UNKNOWN_PERSON, "Persona no reconocida en la puerta");
    }
    return new TrackDisplay(TrackAppCode.ACTIVITY, "Actividad en la puerta");
  }

  private Set<Signal> parse(String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Signal::valueOf)
        .collect(Collectors.toSet());
  }
}
