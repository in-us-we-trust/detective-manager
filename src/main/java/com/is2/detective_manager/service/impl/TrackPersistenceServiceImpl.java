package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.model.entity.AssessmentRecord;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.repository.AssessmentRecordRepository;
import com.is2.detective_manager.repository.TrackRepository;
import com.is2.detective_manager.service.TrackPersistenceService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrackPersistenceServiceImpl implements TrackPersistenceService {

  private final TrackRepository trackRepository;
  private final AssessmentRecordRepository assessmentRecordRepository;

  @Override
  @Transactional
  public Track findOrCreateTrack(UUID trackId, UUID deviceId) {
    return trackRepository
        .findById(trackId)
        .orElseGet(() -> createTrack(trackId, deviceId));
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

  @Override
  @Transactional
  public AssessmentRecord saveStepResult(
      Track track,
      String step,
      List<Signal> stepSignals,
      List<Signal> accumulatedSignals,
      Assessment assessment,
      long durationMs,
      ChunkType mediaType,
      String mediaUrl) {

    // 1) crear el AssessmentRecord de este step
    AssessmentRecord record = new AssessmentRecord();
    record.setTrack(track);
    record.setStep(step);
    record.setSignals(toCsv(stepSignals));
    record.setSeverity(assessment.getSeverity());
    record.setVerdict(assessment.getVerdict());
    record.setMessage(assessment.getMessage());
    record.setDurationMs(durationMs);
    record.setMediaType(mediaType);
    record.setMediaUrl(mediaUrl);
    record.setCreatedAt(LocalDateTime.now());
    assessmentRecordRepository.save(record);

    // 2) actualizar el track con el estado acumulado
    track.setCurrentStep(step);
    track.setAccumulatedSignals(toCsv(accumulatedSignals));
    track.setFinalSeverity(assessment.getSeverity());
    track.setFinalVerdict(assessment.getVerdict());
    track.setStatus(
        assessment.getVerdict() == Verdict.CONTINUE
            ? TrackStatus.CREATED
            : TrackStatus.CLOSED);
    track.setUpdatedAt(LocalDateTime.now());
    trackRepository.save(track);

    log.info(
        "Guardado assessment: track={} step={} signals=[{}] verdict={}",
        track.getId(),
        step,
        record.getSignals(),
        assessment.getVerdict());

    return record;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Track> findTracksByDevice(UUID deviceId) {
    return trackRepository.findAllByDeviceId(deviceId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> findMostSevereImageUrl(UUID trackId) {
    return assessmentRecordRepository.findAllByTrackIdOrderByCreatedAtAsc(trackId).stream()
        .filter(a -> a.getMediaType() == ChunkType.IMAGE)
        .filter(a -> a.getMediaUrl() != null && !a.getMediaUrl().isBlank())
        // el más peligroso primero (mayor level de severity)
        .max(Comparator.comparingInt(a -> a.getSeverity().getLevel()))
        .map(AssessmentRecord::getMediaUrl);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Signal> readAccumulatedSignals(Track track) {
    return fromCsv(track.getAccumulatedSignals());
  }

  /** Reconstruye la lista de señales desde el CSV. "" o null -> lista vacía. */
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

  /** Convierte una lista de señales a CSV: [UNKNOWN_PERSON, WEAPON] -> "UNKNOWN_PERSON,WEAPON". */
  private String toCsv(List<Signal> signals) {
    if (signals == null || signals.isEmpty()) {
      return "";
    }
    return signals.stream().map(Enum::name).collect(Collectors.joining(","));
  }
}
