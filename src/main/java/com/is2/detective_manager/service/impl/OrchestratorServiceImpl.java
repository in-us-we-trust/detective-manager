package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.AnalysisPipeline;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.service.OrchestratorService;
import com.is2.detective_manager.service.TrackConsolidation;
import com.is2.detective_manager.service.TrackPersistenceService;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestratorServiceImpl implements OrchestratorService {

  private final AnalysisPipeline pipeline;
  private final TrackPersistenceService trackPersistenceService;
  private final NotificationPublisher notificationPublisher;
  private final CloudinaryStorageClient cloudinaryStorageClient;

  private final ExecutorService analysisExecutor;

  /** Reintentos de la consolidación ante conflicto de versión (otro request en paralelo). */
  private static final int MAX_CONSOLIDATION_ATTEMPTS = 5;

  @Override
  public EventChunkResponseDto handleEvent(
      String trackIdStr, String deviceIdStr, ChunkType mediaType, byte[] payload) {
    UUID trackId = UUID.fromString(trackIdStr);
    UUID deviceId = UUID.fromString(deviceIdStr);
    UUID requestId = UUID.randomUUID();
    log.info(
        "Procesando chunk requestId={} trackId={} device={} mediaType={}",
        requestId,
        trackId,
        deviceId,
        mediaType);

    Track track = trackPersistenceService.findOrCreateTrack(trackId, deviceId);

    // Si el chunk es una imagen, la subimos UNA vez a Cloudinary.
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
      return buildResponse(trackIdStr, mediaType, null);
    }

    // === FASE 1: ANÁLISIS (steps EN PARALELO, luego persistencia secuencial) ===
    // Los steps de un chunk son independientes: corren en paralelo (la parte lenta
    // es el ML). Una "barrera" espera a que todos terminen. Si un step falla, se
    // continúa con las señales de los que sí terminaron.
    StepContext context =
        StepContext.builder()
            .trackId(trackIdStr)
            .deviceId(deviceId)
            .mediaType(mediaType)
            .payload(payload)
            .accumulated(Map.of())
            .build();

    List<CompletableFuture<StepOutcome>> futures = new ArrayList<>();
    for (AnalysisStep step : steps) {
      futures.add(CompletableFuture.supplyAsync(() -> runStep(step, context), analysisExecutor));
    }

    // BARRERA: esperar a que todos terminen (ok o con error).
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // Ya en el thread principal (secuencial): juntar resultados, persistir.
    List<Signal> chunkSignals = new ArrayList<>();
    for (CompletableFuture<StepOutcome> future : futures) {
      StepOutcome outcome = future.join();
      if (outcome == null) {
        continue; // el step falló; seguimos con los demás
      }
      chunkSignals.addAll(outcome.signals());
      trackPersistenceService.saveAssessment(
          track,
          requestId,
          outcome.stepName(),
          outcome.signals(),
          outcome.durationMs(),
          mediaType,
          mediaUrl);
    }

    // === FASE 2: CONSOLIDACIÓN (protegida, con optimistic retry) ===
    // Recalcula el veredicto sobre TODAS las señales del track. Le pasamos las
    // señales de ESTE chunk (en memoria): en el camino feliz evita releer los
    // assessments; ante conflicto, la consolidación relee de la DB.
    TrackConsolidation consolidation = consolidateWithRetry(trackId, chunkSignals);

    // === FASE 3: NOTIFICACIÓN (efecto externo, DESPUÉS del commit) ===
    if (consolidation.shouldNotify()) {
      maybeNotifyDanger(trackId, deviceId, consolidation.finalAssessment());
    }

    return buildResponse(trackIdStr, mediaType, consolidation);
  }

  /** Corre un step capturando errores: si falla, devuelve null (se ignora ese step). */
  private StepOutcome runStep(AnalysisStep step, StepContext context) {
    String stepName = step.getClass().getSimpleName();
    try {
      long startedAt = System.currentTimeMillis();
      StepResult result = step.execute(context);
      long durationMs = System.currentTimeMillis() - startedAt;
      log.info(
          "Step {} ejecutado ({} ms) -> signals={}", stepName, durationMs, result.getSignals());
      return new StepOutcome(stepName, result.getSignals(), durationMs);
    } catch (Exception e) {
      log.error("Step {} falló; se continúa con los demás", stepName, e);
      return null;
    }
  }

  /** Resultado de un step ya ejecutado (para juntar tras la barrera). */
  private record StepOutcome(String stepName, List<Signal> signals, long durationMs) {}

  /** Consolida reintentando ante conflicto de versión (cada intento es una tx nueva). */
  private TrackConsolidation consolidateWithRetry(UUID trackId, List<Signal> chunkSignals) {
    ObjectOptimisticLockingFailureException lastConflict = null;
    for (int attempt = 1; attempt <= MAX_CONSOLIDATION_ATTEMPTS; attempt++) {
      try {
        // attempt==1: usa las señales en memoria (evita releer). Reintentos: relee.
        boolean firstAttempt = attempt == 1;
        return trackPersistenceService.consolidateTrack(trackId, chunkSignals, firstAttempt);
      } catch (ObjectOptimisticLockingFailureException conflict) {
        lastConflict = conflict;
        log.warn(
            "Conflicto de versión consolidando track {} (intento {}/{}). Reintentando.",
            trackId,
            attempt,
            MAX_CONSOLIDATION_ATTEMPTS);
      }
    }
    log.error(
        "No se pudo consolidar el track {} tras {} intentos", trackId, MAX_CONSOLIDATION_ATTEMPTS);
    throw lastConflict;
  }

  private void maybeNotifyDanger(UUID trackId, UUID deviceId, Assessment assessment) {
    Optional<String> imageUrl = trackPersistenceService.findMostSevereImageUrl(trackId);
    if (imageUrl.isEmpty()) {
      log.info("DANGER en track {} pero sin imagen: no se envía notificación.", trackId);
      return;
    }

    IntruderNotificationMessageDto message =
        IntruderNotificationMessageDto.builder()
            .severity("high")
            .tags(List.of("danger", "doorbell"))
            .deviceId(deviceId)
            .time(OffsetDateTime.now().format(DateTimeFormatter.ISO_INSTANT))
            .title("Peligro detectado")
            .message(assessment.getMessage())
            .imageUrl(imageUrl.get())
            .build();

    try {
      notificationPublisher.publishIntruderDetected(message);
      log.info("Notificación de peligro publicada para track {}", trackId);
    } catch (Exception e) {
      log.error("No se pudo publicar la notificación de peligro (se continúa igual)", e);
    }
  }

  private EventChunkResponseDto buildResponse(
      String trackIdStr, ChunkType mediaType, TrackConsolidation consolidation) {

    Assessment finalA = consolidation != null ? consolidation.finalAssessment() : null;

    return EventChunkResponseDto.builder()
        .trackId(trackIdStr)
        .chunkType(mediaType.name())
        .finalVerdict(finalA != null ? finalA.getVerdict().name() : null)
        .finalSeverity(finalA != null ? finalA.getSeverity().name() : null)
        .message(finalA != null ? finalA.getMessage() : null)
        .build();
  }
}
