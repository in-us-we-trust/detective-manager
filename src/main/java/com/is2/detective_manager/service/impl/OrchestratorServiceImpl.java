package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.mapper.AssessmentMapper;
import com.is2.detective_manager.model.dto.AssessmentDto;
import com.is2.detective_manager.model.dto.AssessmentMessageDto;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.AnalysisPipeline;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.DangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.service.OrchestratorService;
import com.is2.detective_manager.service.TrackPersistenceService;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
  private final NotificationPublisher notificationPublisher;
  private final CloudinaryStorageClient cloudinaryStorageClient;

  @Override
  public EventChunkResponseDto handleEvent(
      String trackIdStr, String deviceIdStr, ChunkType mediaType, byte[] payload) {
    UUID trackId = UUID.fromString(trackIdStr);
    UUID deviceId = UUID.fromString(deviceIdStr);
    log.info("Procesando chunk para trackId={} device={} mediaType={}", trackId, deviceId, mediaType);

    Track track = trackPersistenceService.findOrCreateTrack(trackId, deviceId);
    List<Signal> accumulatedSignals = trackPersistenceService.readAccumulatedSignals(track);
    log.info("Señales acumuladas previas: {}", accumulatedSignals);

    // Si el chunk es una imagen, la subimos UNA vez a Cloudinary y reusamos la URL.
    String mediaUrl = null;
    if (mediaType == ChunkType.IMAGE) {
      try {
        mediaUrl = cloudinaryStorageClient.uploadImageBytes(payload, deviceId);
        log.info("Imagen subida a Cloudinary: {}", mediaUrl);
      } catch (Exception e) {
        log.error("No se pudo subir la imagen a Cloudinary (se continúa sin URL)", e);
      }
    }

    List<AnalysisStep> steps = pipeline.stepsFor(mediaType);
    if (steps.isEmpty()) {
      log.info("No hay steps para mediaType={}", mediaType);
      return buildResponse(trackIdStr, mediaType, track, List.of());
    }

    List<Assessment> assessments = new ArrayList<>();
    Assessment lastAssessment = null;

    for (AnalysisStep step : steps) {
      String stepName = step.getClass().getSimpleName();

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

      accumulatedSignals.addAll(result.getSignals());
      Assessment assessment = dangerEvaluator.evaluate(accumulatedSignals);
      assessments.add(assessment);
      lastAssessment = assessment;

      trackPersistenceService.saveStepResult(
          track, stepName, result.getSignals(), accumulatedSignals, assessment, durationMs,
          mediaType, mediaUrl);
      publishAssessment(trackIdStr, stepName, assessment);

      if (assessment.getVerdict() != Verdict.CONTINUE) {
        log.info("Corte temprano ({}) tras step {}", assessment.getVerdict(), stepName);
        break;
      }
    }

    // Si el veredicto final es STOP_DANGER, disparar la notificación de intruso
    // (solo si tenemos una imagen para mostrar).
    if (lastAssessment != null && lastAssessment.getVerdict() == Verdict.STOP_DANGER) {
      maybeNotifyDanger(trackId, deviceId, lastAssessment);
    }

    return buildResponse(trackIdStr, mediaType, track, assessments);
  }

  private void maybeNotifyDanger(UUID trackId, UUID deviceId, Assessment assessment) {
    Optional<String> imageUrl = trackPersistenceService.findMostSevereImageUrl(trackId);
    if (imageUrl.isEmpty()) {
      log.info("STOP_DANGER en track {} pero sin imagen: no se envía notificación.", trackId);
      return;
    }

    IntruderNotificationMessageDto message =
        IntruderNotificationMessageDto.builder()
            .severity("high")
            .tags(List.of("danger", "doorbell"))
            .deviceId(deviceId)
            .time(OffsetDateTime.now().format(DateTimeFormatter.ISO_INSTANT))
            .title("Peligro detectado")
            .message(assessment.getMessage()) // el motivo concreto, ej. "La persona está armada"
            .imageUrl(imageUrl.get())
            .build();

    try {
      notificationPublisher.publishIntruderDetected(message);
      log.info("Notificación de peligro publicada para track {}", trackId);
    } catch (Exception e) {
      log.error("No se pudo publicar la notificación de peligro (se continúa igual)", e);
    }
  }

  private void publishAssessment(String trackId, String step, Assessment assessment) {
    AssessmentMessageDto message =
        AssessmentMessageDto.builder()
            .trackId(trackId)
            .step(step)
            .status(assessment.getStatus().name())
            .severity(assessment.getSeverity().name())
            .message(assessment.getMessage())
            .verdict(assessment.getVerdict().name())
            .build();
    try {
      notificationPublisher.publishAssessment(message);
    } catch (Exception exception) {
      log.error("No se pudo publicar el assessment a la cola (se continúa igual)", exception);
    }
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
