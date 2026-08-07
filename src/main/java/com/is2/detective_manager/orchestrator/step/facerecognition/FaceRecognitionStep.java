package com.is2.detective_manager.orchestrator.step.facerecognition;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Step 1 del diagrama: "¿Es la imagen reconocida por las cargadas del timbre?".
 * El step es siempre este; el colaborador FaceRecognizer decide real vs mock.
 */
@Component
@RequiredArgsConstructor
public class FaceRecognitionStep implements AnalysisStep {

  private final FaceRecognizer faceRecognizer;

  @Override
  public boolean supports(Set<ChunkType> availableInputs) {
    return availableInputs.contains(ChunkType.IMAGE);
  }

  @Override
  public StepResult execute(StepContext context) {
    boolean known = faceRecognizer.isKnown(context.getPayload());

    StepResult.StepResultBuilder result =
        StepResult.builder()
            .nextStatus(TrackStatus.FACE_RECOGNIZED)
            .data(Map.of("faceKnown", known))
            .shouldNotify(false);

    // Emitimos la señal según si la cara es conocida o no.
    result.signal(known ? Signal.KNOWN_PERSON : Signal.UNKNOWN_PERSON);

    return result.build();
  }
}
