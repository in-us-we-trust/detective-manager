package com.is2.detective_manager.mapper;

import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Construye la presentación de un track para la app: un CODE estable (para mapear a íconos/colores)
 * + un TITLE legible. Ambos salen de la MISMA decisión por prioridad, así siempre son coherentes.
 *
 * <p>Es lógica de PRESENTACIÓN, independiente del RuleBasedDangerEvaluator. Un track tiene varias
 * señales a la vez; el code es UNA categoría que resume, elegida por prioridad.
 */
@Component
public class TrackMessageBuilder {

  public TrackDisplay build(String accumulatedSignalsCsv) {
    Set<Signal> signals = parse(accumulatedSignalsCsv);

    if (signals.contains(Signal.WEAPON_DETECTED)) {
      return new TrackDisplay(TrackAppCode.WEAPON, "Persona armada en la puerta");
    }
    if (signals.contains(Signal.THREAT)) {
      return new TrackDisplay(TrackAppCode.THREAT, "Amenaza detectada en la puerta");
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
