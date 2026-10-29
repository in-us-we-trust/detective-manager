package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.model.entity.AssessmentRecord;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.DangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.orchestrator.evaluator.SignalSeverity;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoPresenceChunk;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VisitPresenceAccumulator;
import com.is2.detective_manager.repository.AssessmentRecordRepository;
import com.is2.detective_manager.repository.TrackRepository;
import com.is2.detective_manager.service.TrackConsolidation;
import com.is2.detective_manager.service.TrackPersistenceService;
import com.is2.detective_manager.validation.audit.ValidationAuditRecorder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrackPersistenceServiceImpl implements TrackPersistenceService {

  private final TrackRepository trackRepository;
  private final AssessmentRecordRepository assessmentRecordRepository;
  private final DangerEvaluator dangerEvaluator;
  private final VideoAnalysisProperties videoProperties;

  private ValidationAuditRecorder validationAuditRecorder;

  @Autowired(required = false)
  public void setValidationAuditRecorder(ValidationAuditRecorder validationAuditRecorder) {
    this.validationAuditRecorder = validationAuditRecorder;
  }

  @Override
  @Transactional
  public Track findOrCreateTrack(UUID trackId, UUID deviceId) {
    return trackRepository.findById(trackId).orElseGet(() -> createTrack(trackId, deviceId));
  }

  private Track createTrack(UUID trackId, UUID deviceId) {
    log.info("Creando track nuevo id={} device={}", trackId, deviceId);
    Track track = new Track();
    track.setId(trackId);
    track.setDeviceId(deviceId);
    track.setStatus(TrackStatus.CREATED);
    track.setCreatedAt(LocalDateTime.now());
    track.setUpdatedAt(LocalDateTime.now());
    return trackRepository.save(track);
  }

  /**
   * Inserta un assessment (append-only). NO toca el track: es seguro ante concurrencia porque solo
   * agrega una fila.
   */
  @Override
  @Transactional
  public AssessmentRecord saveAssessment(
      Track track,
      UUID requestId,
      String step,
      List<Signal> stepSignals,
      long durationMs,
      ChunkType mediaType,
      String mediaUrl) {
    return saveAssessment(
        track, requestId, step, stepSignals, durationMs, mediaType, mediaUrl, null);
  }

  @Override
  @Transactional
  public AssessmentRecord saveAssessment(
      Track track,
      UUID requestId,
      String step,
      List<Signal> stepSignals,
      long durationMs,
      ChunkType mediaType,
      String mediaUrl,
      VideoPresenceChunk presence) {

    AssessmentRecord record = new AssessmentRecord();
    record.setTrack(track);
    record.setRequestId(requestId);
    record.setStep(step);
    record.setSignals(toCsv(stepSignals));
    record.setDurationMs(durationMs);
    record.setMediaType(mediaType);
    record.setMediaUrl(mediaUrl);
    if (presence != null && mediaType == ChunkType.VIDEO) {
      record.setVideoChunkKey(presence.key());
      record.setVideoChunkNumber(presence.number());
      record.setVideoPresenceSeconds(presence.observedSeconds());
      record.setVideoReceivedAt(presence.receivedAt());
    }
    record.setCreatedAt(LocalDateTime.now());
    assessmentRecordRepository.save(record);

    log.info(
        "Assessment guardado: track={} step={} signals=[{}]",
        track.getId(),
        step,
        record.getSignals());
    return record;
  }

  /** Deriva las señales acumuladas juntando las de TODOS los assessments del track. */
  @Override
  @Transactional(readOnly = true)
  public List<Signal> readSignalsFromAssessments(UUID trackId) {
    List<Signal> all = new ArrayList<>();
    for (AssessmentRecord record :
        assessmentRecordRepository.findAllByTrackIdOrderByCreatedAtAsc(trackId)) {
      all.addAll(fromCsv(record.getSignals()));
    }
    return all;
  }

  /**
   * Un intento de consolidación, en su propia transacción. Recalcula verdict/ severity sobre TODAS
   * las señales derivadas de los assessments y escribe el track con optimistic locking (@Version).
   * Si otro request consolidó en el medio, al commitear Hibernate lanza
   * ObjectOptimisticLockingFailureException. El REINTENTO lo maneja quien llama (el orquestador),
   * porque cada reintento debe ser una transacción nueva y un self-invoke no
   * respetaría @Transactional.
   */
  @Override
  @Transactional
  public TrackConsolidation consolidateTrack(
      UUID trackId, List<Signal> chunkSignals, boolean useMemory) {
    Track track =
        trackRepository
            .findById(trackId)
            .orElseThrow(() -> new IllegalStateException("Track no existe: " + trackId));

    // 1) Conjunto completo de señales.
    //    Primer intento: lo de antes (accumulated del track) + lo nuevo (en memoria),
    //    Reintento: reconstruir todas las señales de la DB (hay cambios de otros).
    //    Los tiempos de presencia siempre se reconstruyen desde los clips persistidos.
    List<AssessmentRecord> assessments =
        assessmentRecordRepository.findAllByTrackIdOrderByCreatedAtAsc(trackId);
    List<Signal> allSignals;
    if (useMemory) {
      allSignals = fromCsv(track.getAccumulatedSignals());
      allSignals.addAll(chunkSignals);
    } else {
      allSignals = new ArrayList<>();
      assessments.forEach(record -> allSignals.addAll(fromCsv(record.getSignals())));
    }

    List<VideoPresenceChunk> videoChunks =
        assessments.stream()
            .filter(record -> record.getMediaType() == ChunkType.VIDEO)
            .map(
                record ->
                    new VideoPresenceChunk(
                        record.getVideoChunkKey(),
                        record.getVideoChunkNumber(),
                        record.getVideoPresenceSeconds(),
                        record.getVideoReceivedAt() != null
                            ? record.getVideoReceivedAt()
                            : record.getCreatedAt()))
            .toList();
    double threshold = videoProperties.getVisitProlongedPresenceSeconds();
    if (Double.isFinite(threshold)
        && threshold > 0
        && VisitPresenceAccumulator.maximumObservedSeconds(videoChunks) + 1e-6 >= threshold) {
      allSignals.add(Signal.PROLONGED_VISIT_PRESENCE);
    }

    // 2) Recalcular el veredicto sobre el conjunto completo.
    Assessment assessment = dangerEvaluator.evaluate(allSignals);

    // 3) ¿Corresponde notificar? Solo si es DANGER y aún no se notificó.
    boolean shouldNotify = assessment.getVerdict() == Verdict.DANGER && !track.isNotificationSent();

    // 4) Actualizar el track (se escribe con @Version).
    track.setAccumulatedSignals(toCsv(allSignals));
    track.setFinalSeverity(assessment.getSeverity());
    track.setFinalVerdict(assessment.getVerdict());
    track.setStatus(
        assessment.getVerdict() == Verdict.CONTINUE ? TrackStatus.CREATED : TrackStatus.CLOSED);
    if (shouldNotify) {
      track.setNotificationSent(true);
    }
    track.setUpdatedAt(LocalDateTime.now());
    trackRepository.save(track); // aquí puede saltar OptimisticLockException

    log.info(
        "Track {} consolidado: verdict={} severity={} shouldNotify={}",
        trackId,
        assessment.getVerdict(),
        assessment.getSeverity(),
        shouldNotify);

    if (validationAuditRecorder != null) {
      validationAuditRecorder.consolidationSnapshot(trackId, allSignals, assessment, shouldNotify);
    }
    return new TrackConsolidation(assessment, shouldNotify);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> findMostSevereImageUrl(UUID trackId) {
    return assessmentRecordRepository.findAllByTrackIdOrderByCreatedAtAsc(trackId).stream()
        .filter(a -> a.getMediaType() == ChunkType.IMAGE)
        .filter(a -> a.getMediaUrl() != null && !a.getMediaUrl().isBlank())
        .max(Comparator.comparingInt(a -> SignalSeverity.maxOf(fromCsv(a.getSignals())).getLevel()))
        .map(AssessmentRecord::getMediaUrl);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Track> findTracksByDevice(UUID deviceId) {
    return trackRepository.findAllByDeviceId(deviceId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Track> findTrackById(UUID trackId) {
    return trackRepository.findById(trackId);
  }

  private List<Signal> fromCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return new ArrayList<>();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Signal::valueOf)
        .collect(Collectors.toList());
  }

  private String toCsv(List<Signal> signals) {
    if (signals == null || signals.isEmpty()) {
      return "";
    }
    // sin duplicados, preservando orden de aparición
    return signals.stream().map(Enum::name).distinct().collect(Collectors.joining(","));
  }
}
