package com.is2.detective_manager.orchestrator;

import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionStep;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectRecognitionStep;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Define el ORDEN canónico de los steps: imagen -> audio -> video.
 * El orquestador recorre esta lista en secuencia. Los steps cuyo input no llegó
 * se saltean (via supports()), pero los que corren respetan este orden.
 *
 * El orden se declara explícito acá (no se delega a cómo Spring inyecte la lista),
 * para que sea inequívoco y fácil de leer.
 */
@Component
@RequiredArgsConstructor
public class AnalysisPipeline {

  // --- IMAGEN ---
  private final FaceRecognitionStep faceRecognitionStep;
  private final ObjectRecognitionStep objectRecognitionStep;
  // --- AUDIO (a futuro) ---
  // private final SpeechToTextStep speechToTextStep;
  // --- VIDEO (a futuro) ---

  /** Los steps en el orden en que deben evaluarse. */
  public List<AnalysisStep> steps() {
    return List.of(
        faceRecognitionStep,
        objectRecognitionStep
        // speechToTextStep,
        );
  }
}
