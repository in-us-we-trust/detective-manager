package com.is2.detective_manager.orchestrator.step.facerecognition;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Step 1 del diagrama: "¿Es la imagen reconocida por las cargadas del timbre?". El step es siempre
 * este; el colaborador FaceRecognizer decide real vs mock.
 */
@Component
@RequiredArgsConstructor
public class FaceRecognitionStep implements AnalysisStep {

  /** Clave en {@link StepResult#getData()} con el nombre cuando la persona es conocida. */
  public static final String PERSON_NAME_DATA_KEY = "personName";

  public static final String PERSON_NAMES_DATA_KEY = "personNames";
  public static final String FACES_DATA_KEY = "faces";
  public static final String FACE_COUNT_DATA_KEY = "faceCount";
  public static final String KNOWN_FACE_COUNT_DATA_KEY = "knownFaceCount";
  public static final String UNKNOWN_FACE_COUNT_DATA_KEY = "unknownFaceCount";

  private final FaceRecognizer faceRecognizer;

  @Override
  public boolean supports(Set<ChunkType> availableInputs) {
    return availableInputs.contains(ChunkType.IMAGE);
  }

  @Override
  public StepResult execute(StepContext context) {
    FaceRecognitionOutcome outcome =
        faceRecognizer.recognize(context.getPayload(), context.getDeviceId());

    Map<String, Object> data = new HashMap<>();
    data.put("faceKnown", outcome.known());
    if (outcome.known() && outcome.personName() != null && !outcome.personName().isBlank()) {
      data.put(PERSON_NAME_DATA_KEY, outcome.personName());
    }

    StepResult.StepResultBuilder result =
        StepResult.builder().nextStatus(TrackStatus.FACE_RECOGNIZED).data(data).shouldNotify(false);

    if (outcome.known()) {
      result.signal(Signal.KNOWN_PERSON);
    }
    if (outcome.hasUnknownFaces()) {
      result.signal(Signal.UNKNOWN_PERSON);
    }
    if (!outcome.faces().isEmpty()) {
      int faceCount = outcome.faces().size();
      long knownCount = outcome.faces().stream().filter(FaceRecognitionOutcome.Face::known).count();
      data.put(FACES_DATA_KEY, outcome.faces());
      data.put(FACE_COUNT_DATA_KEY, faceCount);
      data.put(KNOWN_FACE_COUNT_DATA_KEY, knownCount);
      data.put(UNKNOWN_FACE_COUNT_DATA_KEY, faceCount - knownCount);
      data.put(PERSON_NAMES_DATA_KEY, outcome.personNames());
      if (faceCount > 1) {
        result.signal(Signal.MULTIPLE_PEOPLE);
        result.message(
            "Rostros reconocidos: "
                + knownCount
                + "; rostros no reconocidos: "
                + (faceCount - knownCount));
        if (outcome.hasUnknownFaces()) {
          data.put("unknownPersonSuggestion", "SKIPPED_MULTIPLE_FACES");
        }
      }
    }

    return result.build();
  }
}
