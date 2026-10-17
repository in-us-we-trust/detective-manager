package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.AnalysisPipeline;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalysisStep;
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionStep;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectRecognitionStep;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisStep;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoPresenceChunk;
import com.is2.detective_manager.service.OrchestratorService;
import com.is2.detective_manager.service.PersonService;
import com.is2.detective_manager.service.TrackConsolidation;
import com.is2.detective_manager.service.TrackPersistenceService;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
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
  private final PersonService personService;

  private final ExecutorService analysisExecutor;

  /** Reintentos de la consolidación ante conflicto de versión (otro request en paralelo). */
  private static final int MAX_CONSOLIDATION_ATTEMPTS = 5;

  @Override
  public EventChunkResponseDto handleEvent(
      String trackIdStr, String deviceIdStr, ChunkType mediaType, byte[] payload) {
    return handleEvent(trackIdStr, deviceIdStr, mediaType, payload, null);
  }

  @Override
  public EventChunkResponseDto handleEvent(
      String trackIdStr, String deviceIdStr, ChunkType mediaType, byte[] payload, String filename) {
    LocalDateTime receivedAt = LocalDateTime.now();
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
    List<StepOutcome> outcomes = new ArrayList<>();
    List<Signal> chunkSignals = new ArrayList<>();
    for (CompletableFuture<StepOutcome> future : futures) {
      StepOutcome outcome = future.join();
      outcomes.add(outcome);
      if (outcome.quality() == AnalysisQuality.FAILED && mediaType != ChunkType.VIDEO) {
        continue; // Conservar el motivo para la respuesta, sin aportar señales del análisis
        // fallido.
      }
      if (outcome.signals() != null) {
        chunkSignals.addAll(outcome.signals());
      }
      if (mediaType == ChunkType.VIDEO) {
        Double observedSeconds = null;
        if (outcome.quality() == AnalysisQuality.VALID
            && outcome.data() != null
            && outcome.data().get(VideoAnalysisStep.FEATURES_DATA_KEY)
                instanceof VideoBehaviorFeatures features) {
          observedSeconds = features.observedPresenceSeconds();
        }
        trackPersistenceService.saveAssessment(
            track,
            requestId,
            outcome.stepName(),
            outcome.signals(),
            outcome.durationMs(),
            mediaType,
            mediaUrl,
            VideoPresenceChunk.from(filename, payload, observedSeconds, receivedAt));
      } else {
        trackPersistenceService.saveAssessment(
            track,
            requestId,
            outcome.stepName(),
            outcome.signals(),
            outcome.durationMs(),
            mediaType,
            mediaUrl);
      }
    }

    // === FASE 2: CONSOLIDACIÓN (protegida, con optimistic retry) ===
    // Recalcula el veredicto sobre TODAS las señales del track. Le pasamos las
    // señales de ESTE chunk (en memoria). Los tiempos se reconstruyen desde la DB;
    // ante conflicto, también se reconstruyen las señales de los assessments.
    TrackConsolidation consolidation = consolidateWithRetry(trackId, chunkSignals);

    String personName = recognizedPersonName(outcomes);
    if (mediaType == ChunkType.IMAGE && chunkSignals.contains(Signal.UNKNOWN_PERSON)) {
      saveUnknownPersonSuggestion(deviceId, payload, mediaUrl);
    }

    Assessment finalAssessment = consolidation.finalAssessment();
    String message =
        messageWithRecognizedPerson(
            analysisMessage(finalAssessment, outcomes, track, chunkSignals), personName);

    // === FASE 3: NOTIFICACIÓN (efecto externo, DESPUÉS del commit) ===
    if (consolidation.shouldNotify()) {
      maybeNotifyDanger(trackId, deviceId, message);
    }

    return buildResponse(
        trackIdStr,
        mediaType,
        consolidation,
        message,
        additionalInformation(mediaType, personName));
  }

  /** Conserva los errores como información del análisis, sin convertirlos en señales. */
  private StepOutcome runStep(AnalysisStep step, StepContext context) {
    String stepName = step.getClass().getSimpleName();
    long startedAt = System.currentTimeMillis();
    try {
      StepResult result = step.execute(context);
      if (result == null || result.getQuality() == null) {
        throw new IllegalStateException("El step devolvió un resultado inválido");
      }
      long durationMs = System.currentTimeMillis() - startedAt;
      log.info(
          "Step {} ejecutado ({} ms) -> quality={} signals={}",
          stepName,
          durationMs,
          result.getQuality(),
          result.getSignals());
      return new StepOutcome(
          stepName,
          result.getSignals(),
          result.getData(),
          durationMs,
          result.getQuality(),
          result.getMessage());
    } catch (Exception e) {
      log.error("Step {} falló; se continúa con los demás", stepName, e);
      return new StepOutcome(
          stepName,
          List.of(),
          Map.of(),
          System.currentTimeMillis() - startedAt,
          AnalysisQuality.FAILED,
          failureMessage(step, context.getMediaType()));
    }
  }

  private String failureMessage(AnalysisStep step, ChunkType mediaType) {
    String source;
    if (step instanceof FaceRecognitionStep) {
      source = "reconocimiento facial";
    } else if (step instanceof ObjectRecognitionStep) {
      source = "objetos";
    } else if (step instanceof ConversationAnalysisStep) {
      source = "audio";
    } else if (step instanceof VideoAnalysisStep) {
      source = "video";
    } else {
      source =
          mediaType == ChunkType.IMAGE
              ? "imagen"
              : mediaType == ChunkType.AUDIO ? "audio" : "video";
    }
    return "No se pudo completar el análisis de " + source;
  }

  private String analysisMessage(
      Assessment assessment, List<StepOutcome> outcomes, Track track, List<Signal> chunkSignals) {
    List<String> details =
        outcomes.stream()
            .map(StepOutcome::message)
            .filter(detail -> detail != null && !detail.isBlank())
            .distinct()
            .toList();
    String message = assessment != null ? assessment.getMessage() : null;
    boolean noPreviousSignals =
        track.getAccumulatedSignals() == null || track.getAccumulatedSignals().isBlank();
    // La conclusión genérica no describe un análisis sin datos útiles. Conservar, en cambio,
    // cualquier hallazgo válido anterior (incluido SAFE) y todas las alertas de mayor severidad.
    if (!details.isEmpty()
        && chunkSignals.isEmpty()
        && noPreviousSignals
        && assessment != null
        && assessment.getVerdict() == Verdict.CONTINUE
        && assessment.getSeverity() == Severity.NORMAL) {
      message = null;
    }
    List<String> parts = new ArrayList<>();
    if (message != null && !message.isBlank()) {
      parts.add(message);
    }
    for (String detail : details) {
      if (!parts.contains(detail)) {
        parts.add(detail);
      }
    }
    return parts.isEmpty() ? message : String.join(" · ", parts);
  }

  /** Resultado de un step ya ejecutado (para juntar tras la barrera). */
  private record StepOutcome(
      String stepName,
      List<Signal> signals,
      Map<String, Object> data,
      long durationMs,
      AnalysisQuality quality,
      String message) {}

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

  private void saveUnknownPersonSuggestion(UUID deviceId, byte[] payload, String imageUrl) {
    if (payload == null || payload.length == 0) {
      log.warn("Persona desconocida sin imagen: no se guarda embedding de sugerencia");
      return;
    }

    log.info("Persona desconocida en device {}: guardando embedding para sugerencia", deviceId);
    personService.saveOrphanEmbeddingIfPossible(
        deviceId, Base64.getEncoder().encodeToString(payload), imageUrl);
  }

  private String recognizedPersonName(List<StepOutcome> outcomes) {
    for (StepOutcome outcome : outcomes) {
      if (outcome.quality() != AnalysisQuality.VALID
          || outcome.signals() == null
          || !outcome.signals().contains(Signal.KNOWN_PERSON)
          || outcome.data() == null) {
        continue;
      }
      Object value = outcome.data().get(FaceRecognitionStep.PERSON_NAME_DATA_KEY);
      if (value instanceof String name && !name.isBlank()) {
        return name.strip();
      }
    }
    return null;
  }

  private String messageWithRecognizedPerson(String message, String personName) {
    if (personName == null || personName.isBlank()) {
      return message;
    }
    String recognized = "Persona reconocida: " + personName;
    if (message == null || message.isBlank() || "Persona reconocida".equals(message)) {
      return recognized;
    }
    if (message.contains(recognized)) {
      return message;
    }
    return message + (message.endsWith(".") ? " " : ". ") + recognized;
  }

  private Map<String, Object> additionalInformation(ChunkType mediaType, String personName) {
    if (mediaType != ChunkType.IMAGE || personName == null || personName.isBlank()) {
      return null;
    }
    return Map.of(FaceRecognitionStep.PERSON_NAME_DATA_KEY, personName);
  }

  private void maybeNotifyDanger(UUID trackId, UUID deviceId, String message) {
    Optional<String> imageUrl = trackPersistenceService.findMostSevereImageUrl(trackId);
    if (imageUrl.isEmpty()) {
      log.info("DANGER en track {} pero sin imagen: no se envía notificación.", trackId);
      return;
    }

    IntruderNotificationMessageDto notification =
        IntruderNotificationMessageDto.builder()
            .severity("high")
            .tags(List.of("danger", "doorbell"))
            .deviceId(deviceId)
            .time(OffsetDateTime.now().format(DateTimeFormatter.ISO_INSTANT))
            .title("Peligro detectado")
            .message(message)
            .imageUrl(imageUrl.get())
            .build();

    try {
      notificationPublisher.publishIntruderDetected(notification);
      log.info("Notificación de peligro publicada para track {}", trackId);
    } catch (Exception e) {
      log.error("No se pudo publicar la notificación de peligro (se continúa igual)", e);
    }
  }

  private EventChunkResponseDto buildResponse(
      String trackIdStr, ChunkType mediaType, TrackConsolidation consolidation) {
    Assessment finalAssessment = consolidation != null ? consolidation.finalAssessment() : null;
    return buildResponse(
        trackIdStr,
        mediaType,
        consolidation,
        finalAssessment != null ? finalAssessment.getMessage() : null,
        null);
  }

  private EventChunkResponseDto buildResponse(
      String trackIdStr,
      ChunkType mediaType,
      TrackConsolidation consolidation,
      String message,
      Map<String, Object> additionalInformation) {

    Assessment finalAssessment = consolidation != null ? consolidation.finalAssessment() : null;

    return EventChunkResponseDto.builder()
        .trackId(trackIdStr)
        .chunkType(mediaType.name())
        .finalVerdict(finalAssessment != null ? finalAssessment.getVerdict().name() : null)
        .finalSeverity(finalAssessment != null ? finalAssessment.getSeverity().name() : null)
        .message(message)
        .additionalInformation(additionalInformation)
        .build();
  }
}
