package com.is2.detective_manager.service;

import com.is2.detective_manager.model.entity.AssessmentRecord;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.UUID;

/** Persistencia de tracks y sus assessments. */
public interface TrackPersistenceService {

  /** Busca el track por su id (=trackId); si no existe, lo crea para ese device. */
  Track findOrCreateTrack(UUID trackId, UUID deviceId);

  /**
   * Guarda el resultado de un step: crea un AssessmentRecord y actualiza el track
   * (señales acumuladas, current_step, veredicto/severity final).
   */
  AssessmentRecord saveStepResult(
      Track track,
      String step,
      List<Signal> stepSignals,
      List<Signal> accumulatedSignals,
      Assessment assessment,
      long durationMs);

  /** Reconstruye las señales acumuladas del track (desde el CSV) como lista. */
  List<Signal> readAccumulatedSignals(Track track);
}
