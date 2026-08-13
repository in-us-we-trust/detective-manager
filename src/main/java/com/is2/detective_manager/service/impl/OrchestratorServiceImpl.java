package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.mapper.AssessmentMapper;
import com.is2.detective_manager.model.dto.AssessmentDto;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.AnalysisPipeline;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.DangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.service.OrchestratorService;
import com.is2.detective_manager.service.TrackPersistenceService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestratorServiceImpl implements OrchestratorService {

  private final AnalysisPipeline pipeline;
  private final DangerEvaluator dangerEvaluator;
  private final TrackPersistenceService trackPersistenceService;

  @Override
  public EventChunkResponseDto handleEvent(
      String trackIdStr, String deviceIdStr, ChunkType mediaType, byte[] payload) {
    UUID trackId = UUID.fromString(trackIdStr);
    UUID deviceId = UUID.fromString(deviceIdStr);
    log.info("Procesando chunk para trackId={} device={} mediaType={}", trackId, deviceId, mediaType);

    // 1) Buscar/crear el track y cargar su estado acumulado.
    Track track = trackPersistenceService.findOrCreateTrack(trackId, deviceId);

    // 2) Determinar el próximo step según hasta dónde llegó el track.
    Optional<AnalysisStep> nextStepOpt = pipeline.nextStep(track.getCurrentStep(), mediaType);

    if (nextStepOpt.isEmpty()) {
      log.info("Track {} ya completó todos los steps. Nada que hacer.", trackId);
      return buildResponse(trackIdStr, mediaType, track, List.of());
    }

    AnalysisStep step = nextStepOpt.get();
    String stepName = step.getClass().getSimpleName();

    // 3) Reconstruir las señales acumuladas de steps anteriores (desde la DB).
    List<Signal> accumulatedSignals = trackPersistenceService.readAccumulatedSignals(track);
    log.info("Señales acumuladas previas: {}", accumulatedSignals);

    // 4) Ejecutar el step (midiendo cuánto tarda).
    StepContext context =
        StepContext.builder()
            .trackId(trackIdStr)
            .mediaType(mediaType)
            .payload(payload)
            .accumulated(Map.of())
            .build();

    long startedAt = System.currentTimeMillis();
    StepResult result = step.execute(context);
    long durationMs = System.currentTimeMillis() - startedAt;

    log.info("Step {} ejecutado ({} ms) -> signals={}", stepName, durationMs, result.getSignals());

    // 5) Acumular las nuevas señales sobre las previas.
    accumulatedSignals.addAll(result.getSignals());

    // 6) Evaluar con TODO lo acumulado.
    Assessment assessment = dangerEvaluator.evaluate(accumulatedSignals);
    log.info("Assessment -> severity={}, msg='{}'", assessment.getSeverity(), assessment.getMessage());

    // 7) Persistir el resultado de este step + actualizar el track.
    trackPersistenceService.saveStepResult(
        track, stepName, result.getSignals(), accumulatedSignals, assessment, durationMs);

    return buildResponse(trackIdStr, mediaType, track, List.of(assessment));
  }

  private EventChunkResponseDto buildResponse(
      String trackIdStr, ChunkType mediaType, Track track, List<Assessment> assessments) {

    List<AssessmentDto> dtos = assessments.stream().map(AssessmentMapper::toDto).toList();

    return EventChunkResponseDto.builder()
        .trackId(trackIdStr)
        .chunkType(mediaType.name())
        .finalVerdict(track.getFinalVerdict() != null ? track.getFinalVerdict().name() : null)
        .finalSeverity(track.getFinalSeverity() != null ? track.getFinalSeverity().name() : null)
        .assessments(dtos)
        .build();
  }
}
