package com.is2.detective_manager.orchestrator;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalysisStep;
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionStep;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectRecognitionStep;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisStep;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Define el ORDEN canónico de los steps. Un request trae un mediaType y se corren TODOS los steps
 * que soportan ese tipo, en orden.
 */
@Component
@RequiredArgsConstructor
public class AnalysisPipeline {

  // --- IMAGEN ---
  private final FaceRecognitionStep faceRecognitionStep;
  private final ObjectRecognitionStep objectRecognitionStep;
  // --- AUDIO ---
  private final ConversationAnalysisStep conversationAnalysisStep;
  // --- VIDEO ---
  private final VideoAnalysisStep videoAnalysisStep;

  public List<AnalysisStep> steps() {
    return List.of(
        faceRecognitionStep, objectRecognitionStep, conversationAnalysisStep, videoAnalysisStep);
  }

  /** Todos los steps que soportan este mediaType, en orden. */
  public List<AnalysisStep> stepsFor(ChunkType mediaType) {
    Set<ChunkType> available = Set.of(mediaType);
    return steps().stream().filter(step -> step.supports(available)).toList();
  }
}
