package com.is2.detective_manager.orchestrator;

import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionStep;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectRecognitionStep;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Define el ORDEN canónico de los steps: imagen -> audio -> video.
 * El orquestador avanza de a un step por request, usando nextStep().
 */
@Component
@RequiredArgsConstructor
public class AnalysisPipeline {

  // --- IMAGEN ---
  private final FaceRecognitionStep faceRecognitionStep;
  private final ObjectRecognitionStep objectRecognitionStep;
  // --- AUDIO (a futuro) ---
  // --- VIDEO (a futuro) ---

  /** Los steps en el orden en que deben evaluarse. */
  public List<AnalysisStep> steps() {
    return List.of(faceRecognitionStep, objectRecognitionStep);
  }

  /**
   * Devuelve el step que sigue después del ejecutado por último.
   * @param lastStepName nombre del último step corrido (null/blank si el track recién arranca)
   * @return el próximo step, o vacío si ya no queda ninguno.
   */
  public Optional<AnalysisStep> nextStep(String lastStepName) {
    List<AnalysisStep> steps = steps();

    // Si no hubo step previo, el próximo es el primero.
    if (lastStepName == null || lastStepName.isBlank()) {
      return steps.isEmpty() ? Optional.empty() : Optional.of(steps.get(0));
    }

    // Buscamos el índice del último ejecutado y devolvemos el siguiente.
    for (int i = 0; i < steps.size(); i++) {
      if (steps.get(i).getClass().getSimpleName().equals(lastStepName)) {
        int nextIndex = i + 1;
        return nextIndex < steps.size() ? Optional.of(steps.get(nextIndex)) : Optional.empty();
      }
    }

    // El último step no está en la lista (raro): no avanzamos.
    return Optional.empty();
  }
}
