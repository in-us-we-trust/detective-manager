package com.is2.detective_manager.service;

import com.is2.detective_manager.model.entity.AssessmentRecord;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistencia de tracks y sus assessments. */
public interface TrackPersistenceService {

  /** Busca el track por su id (=trackId); si no existe, lo crea para ese device. */
  Track findOrCreateTrack(UUID trackId, UUID deviceId);

  /**
   * Guarda el resultado de un step: crea un AssessmentRecord y actualiza el track.
   * Incluye el media_type y media_url del chunk que produjo el assessment.
   */
  AssessmentRecord saveStepResult(
      Track track,
      String step,
      List<Signal> stepSignals,
      List<Signal> accumulatedSignals,
      Assessment assessment,
      long durationMs,
      ChunkType mediaType,
      String mediaUrl);

  /** Reconstruye las señales acumuladas del track (desde el CSV) como lista. */
  List<Signal> readAccumulatedSignals(Track track);

  /**
   * Devuelve la media_url de la IMAGEN del assessment más peligroso del track
   * (mayor severity). Vacío si el track no tiene ninguna imagen guardada.
   */
  Optional<String> findMostSevereImageUrl(UUID trackId);

  /** Lista los tracks de un dispositivo. */
  List<Track> findTracksByDevice(UUID deviceId);

  /** Busca un track por su id (=trackId). Vacío si no existe. */
  Optional<Track> findTrackById(UUID trackId);
}
