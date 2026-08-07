package com.is2.detective_manager.orchestrator.step.objectrecognition;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Step 2 del diagrama: análisis de la imagen (detección de objetos, ej. armas).
 * Corre DESPUÉS del reconocimiento facial, sobre la misma imagen.
 */
@Component
@RequiredArgsConstructor
public class ObjectRecognitionStep implements AnalysisStep {

  private final ObjectRecognizer objectRecognizer;

  @Override
  public boolean supports(Set<ChunkType> availableInputs) {
    return availableInputs.contains(ChunkType.IMAGE);
  }

  @Override
  public StepResult execute(StepContext context) {
    List<String> objects = objectRecognizer.detectObjects(context.getPayload());

    StepResult.StepResultBuilder result =
        StepResult.builder()
            .nextStatus(TrackStatus.IMAGE_ANALYZED)
            .data(Map.of("detectedObjects", objects))
            .shouldNotify(false);

    // Traducimos objetos crudos a señales semánticas.
    boolean weapon = objects.contains("weapon");
    boolean mask = objects.contains("mask");

    if (weapon) {
      result.signal(Signal.WEAPON_DETECTED);
    }
    if (mask) {
      result.signal(Signal.COVERED_FACE);
    }
    if (!weapon && !mask) {
      result.signal(Signal.NO_THREAT_OBJECT);
    }

    return result.build();
  }
}
