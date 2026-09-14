package com.is2.detective_manager.service;

import com.is2.detective_manager.model.entity.AssessmentRecord;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistencia de tracks y sus assessments. */
public interface TrackPersistenceService {

  /** Busca el track por su id (=trackId); si no existe, lo crea para ese device. */
  Track findOrCreateTrack(UUID trackId, UUID deviceId);

  /**
   * Inserta un assessment (append-only). NO toca el track: es seguro ante concurrencia porque solo
   * agrega una fila, sin leer-modificar-escribir estado compartido.
   */
  AssessmentRecord saveAssessment(
      Track track,
      UUID requestId,
      String step,
      List<Signal> stepSignals,
      long durationMs,
      ChunkType mediaType,
      String mediaUrl);

  /**
   * Deriva las señales acumuladas del track juntando las de TODOS sus assessments (fuente de
   * verdad). Independiente de lo que un request individual haya visto.
   */
  List<Signal> readSignalsFromAssessments(UUID trackId);

  /**
   * Consolida el estado del track: recalcula verdict/severity sobre TODAS las señales derivadas de
   * sus assessments, y actualiza el track con optimistic locking + reintento ante conflicto.
   * Devuelve el resultado consolidado (incluye si corresponde disparar la notificación, una sola
   * vez).
   */
  /**
   * Consolida el track sobre el conjunto completo de señales.
   *
   * @param chunkSignals señales del chunk en curso (en memoria)
   * @param useMemory si true (primer intento), arma el conjunto como accumulated_signals del track
   *     + chunkSignals, sin releer assessments. Si false (reintento tras conflicto), relee todas
   *     las señales de la DB.
   */
  TrackConsolidation consolidateTrack(
      UUID trackId, java.util.List<Signal> chunkSignals, boolean useMemory);

  /**
   * Devuelve la media_url de la IMAGEN del assessment más peligroso del track (mayor severity).
   * Vacío si el track no tiene ninguna imagen guardada.
   */
  Optional<String> findMostSevereImageUrl(UUID trackId);

  /** Lista los tracks de un dispositivo. */
  List<Track> findTracksByDevice(UUID deviceId);

  /** Busca un track por su id (=trackId). Vacío si no existe. */
  Optional<Track> findTrackById(UUID trackId);
}
