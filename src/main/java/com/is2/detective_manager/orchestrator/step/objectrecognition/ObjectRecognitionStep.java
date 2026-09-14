package com.is2.detective_manager.orchestrator.step.objectrecognition;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Step de imagen: detección de objetos. Traduce la salida del ML a señales. Dos fuentes: (1)
 * objects con su confidence (umbral por label), y (2) counts (cantidades, ej. más de una persona ->
 * MULTIPLE_PEOPLE).
 */
@Component
@RequiredArgsConstructor
public class ObjectRecognitionStep implements AnalysisStep {

  private final ObjectRecognizer objectRecognizer;

  /** Umbral de confidence por label. Por ahora, todos > 0.8. */
  private static final double DEFAULT_THRESHOLD = 0.3;

  private static final Map<String, Double> THRESHOLDS =
      Map.of(
          "person", DEFAULT_THRESHOLD,
          "covered_face", DEFAULT_THRESHOLD,
          "Firearm", DEFAULT_THRESHOLD,
          "Melee_Weapon", DEFAULT_THRESHOLD,
          "Blunt Weapon", DEFAULT_THRESHOLD,
          "Explosive", DEFAULT_THRESHOLD);

  /** Mapeo label del ML -> Signal del dominio. Las armas van todas a WEAPON_DETECTED. */
  private static final Map<String, Signal> LABEL_TO_SIGNAL =
      Map.of(
          "person", Signal.PERSON,
          "covered_face", Signal.COVERED_FACE,
          "Firearm", Signal.WEAPON_DETECTED,
          "Melee_Weapon", Signal.WEAPON_DETECTED,
          "Blunt Weapon", Signal.WEAPON_DETECTED,
          "Explosive", Signal.WEAPON_DETECTED);

  @Override
  public boolean supports(Set<ChunkType> availableInputs) {
    return availableInputs.contains(ChunkType.IMAGE);
  }

  @Override
  public StepResult execute(StepContext context) {
    ObjectDetectionResult detection = objectRecognizer.detectObjects(context.getPayload());

    // Set para no emitir la misma señal dos veces (ej. dos armas -> un WEAPON_DETECTED).
    Set<Signal> signals = new HashSet<>();

    // Fuente 1: objects. Si su confidence supera el umbral del label, emite su señal.
    for (DetectedObject obj : detection.objects()) {
      double threshold = THRESHOLDS.getOrDefault(obj.label(), DEFAULT_THRESHOLD);
      Signal signal = LABEL_TO_SIGNAL.get(obj.label());
      if (signal != null && obj.confidence() > threshold) {
        signals.add(signal);
      }
    }

    // Fuente 2: counts. Más de una persona -> MULTIPLE_PEOPLE.
    int personCount = detection.counts().getOrDefault("person", 0);
    if (personCount > 1) {
      signals.add(Signal.MULTIPLE_PEOPLE);
    }

    // Si no se detectó ningún objeto amenazante, señal de "todo tranquilo".
    if (!signals.contains(Signal.WEAPON_DETECTED) && !signals.contains(Signal.COVERED_FACE)) {
      signals.add(Signal.NO_THREAT_OBJECT);
    }

    StepResult.StepResultBuilder result =
        StepResult.builder().nextStatus(TrackStatus.IMAGE_ANALYZED).shouldNotify(false);
    signals.forEach(result::signal);

    return result.build();
  }
}
