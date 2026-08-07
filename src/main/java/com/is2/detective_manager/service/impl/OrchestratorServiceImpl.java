package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.model.dto.EventChunkRequestDto;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.AnalysisPipeline;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.DangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.service.OrchestratorService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestratorServiceImpl implements OrchestratorService {

  private final AnalysisPipeline pipeline;
  private final DangerEvaluator dangerEvaluator;

  @Override
  public EventChunkResponseDto handleEvent(EventChunkRequestDto request) {
    log.info("Procesando evento para trackId={}", request.getTrackId());

    // Por ahora: un solo request con una foto -> el único input disponible es IMAGE.
    // (A futuro: esto sale de los chunks acumulados del track.)
    Set<ChunkType> availableInputs = Set.of(ChunkType.IMAGE);

    Map<String, Object> accumulatedData = new HashMap<>();
    List<Signal> accumulatedSignals = new ArrayList<>();

    // "Solo cuando cambia": guardamos un assessment únicamente al cambiar la severity.
    List<Assessment> changes = new ArrayList<>();
    Severity lastSeverity = null;
    Assessment last = null;

    for (AnalysisStep step : pipeline.steps()) {
      if (!step.supports(availableInputs)) {
        log.info("Step {} saltado (input no disponible)", step.getClass().getSimpleName());
        continue;
      }

      StepContext context =
          StepContext.builder()
              .trackId(request.getTrackId())
              .chunkType(ChunkType.IMAGE)
              .payload(request.getImage())
              .accumulated(Map.copyOf(accumulatedData))
              .build();

      StepResult result = step.execute(context);
      log.info(
          "Step {} ejecutado -> data={}, signals={}",
          step.getClass().getSimpleName(),
          result.getData(),
          result.getSignals());

      if (result.getData() != null) {
        accumulatedData.putAll(result.getData());
      }
      accumulatedSignals.addAll(result.getSignals());

      // El orquestador MANDA: evalúa con todas las señales acumuladas.
      Assessment assessment = dangerEvaluator.evaluate(accumulatedSignals);
      last = assessment;

      // Emitimos (guardamos) solo si la severity cambió respecto de la última.
      if (assessment.getSeverity() != lastSeverity) {
        changes.add(assessment);
        lastSeverity = assessment.getSeverity();
        log.info(
            "Cambio de severity -> {} (msg='{}')",
            assessment.getSeverity(),
            assessment.getMessage());
      }

      if (assessment.getVerdict() != Verdict.CONTINUE) {
        log.info("Corte temprano: {}", assessment.getVerdict());
        break;
      }
    }

    return EventChunkResponseDto.builder()
        .trackId(request.getTrackId())
        .chunkType(ChunkType.IMAGE.name())
        .payloadBytes(request.getImage().length())
        .message(
            "verdict="
                + (last != null ? last.getVerdict() : "N/A")
                + ", severity="
                + (last != null ? last.getSeverity() : "N/A")
                + ", message="
                + (last != null ? last.getMessage() : "N/A")
                + ", cambios="
                + changes.size())
        .build();
  }
}
