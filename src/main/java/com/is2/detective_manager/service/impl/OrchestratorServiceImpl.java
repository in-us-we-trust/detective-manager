package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.model.dto.EventChunkRequestDto;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.AnalysisPipeline;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.DangerEvaluator;
import com.is2.detective_manager.service.OrchestratorService;
import java.util.HashMap;
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

    // Data que se va acumulando a medida que corren los steps.
    Map<String, Object> accumulated = new HashMap<>();

    Verdict verdict = Verdict.CONTINUE;

    for (AnalysisStep step : pipeline.steps()) {
      // Saltear los steps cuyo input no llegó (respetando el orden de los que sí corren).
      if (!step.supports(availableInputs)) {
        log.info("Step {} saltado (input no disponible)", step.getClass().getSimpleName());
        continue;
      }

      StepContext context =
          StepContext.builder()
              .trackId(request.getTrackId())
              .chunkType(ChunkType.IMAGE) // por ahora fijo; a futuro depende del chunk
              .payload(request.getImage())
              .accumulated(Map.copyOf(accumulated))
              .build();

      StepResult result = step.execute(context);
      log.info("Step {} ejecutado -> {}", step.getClass().getSimpleName(), result.getData());

      // El orquestador acumula el aporte del step.
      if (result.getData() != null) {
        accumulated.putAll(result.getData());
      }

      // El orquestador MANDA: consulta la lógica de peligro tras cada step.
      verdict = dangerEvaluator.evaluate(accumulated);
      if (verdict != Verdict.CONTINUE) {
        log.info("Corte temprano con verdict={} tras step {}", verdict, step.getClass().getSimpleName());
        break;
      }
    }

    return EventChunkResponseDto.builder()
        .trackId(request.getTrackId())
        .chunkType(ChunkType.IMAGE.name())
        .payloadBytes(request.getImage().length())
        .message("Pipeline finalizado. verdict=" + verdict + ", data=" + accumulated)
        .build();
  }
}
