package com.is2.detective_manager.orchestrator;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalysisStep;
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionStep;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectRecognitionStep;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Define el ORDEN canónico de los steps: imagen -> audio -> video.
 * El orquestador avanza de a un step por request, usando nextStep(): el próximo
 * step DESPUÉS del último ejecutado que además soporte el mediaType que llegó.
 */
@Component
@RequiredArgsConstructor
public class AnalysisPipeline {

  // --- IMAGEN ---
  private final FaceRecognitionStep faceRecognitionStep;
  private final ObjectRecognitionStep objectRecognitionStep;
  // --- AUDIO ---
  private final ConversationAnalysisStep conversationAnalysisStep;

  public List<AnalysisStep> steps() {
    return List.of(faceRecognitionStep, objectRecognitionStep, conversationAnalysisStep);
  }

  /**
   * Próximo step a ejecutar: el primero, después del último ejecutado, que soporte
   * el mediaType recibido. Así una imagen solo dispara steps de imagen, un audio
   * solo los de audio, respetando el orden.
   *
   * @param lastStepName último step corrido para este track (null si recién arranca)
   * @param mediaType tipo del chunk que llegó en este request
   */
  public Optional<AnalysisStep> nextStep(String lastStepName, ChunkType mediaType) {
    List<AnalysisStep> steps = steps();
    Set<ChunkType> available = Set.of(mediaType);

    // índice desde donde buscar: justo después del último ejecutado.
    int startIndex = 0;
    if (lastStepName != null && !lastStepName.isBlank()) {
      for (int i = 0; i < steps.size(); i++) {
        if (steps.get(i).getClass().getSimpleName().equals(lastStepName)) {
          startIndex = i + 1;
          break;
        }
      }
    }

    // el primero, de ahí en adelante, que soporte este mediaType.
    for (int i = startIndex; i < steps.size(); i++) {
      if (steps.get(i).supports(available)) {
        return Optional.of(steps.get(i));
      }
    }
    return Optional.empty();
  }
}
