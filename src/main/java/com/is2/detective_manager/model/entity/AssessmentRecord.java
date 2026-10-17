package com.is2.detective_manager.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;

/**
 * Registro persistido del resultado de un step dentro de un track (relación 1..N). Distinto de
 * orchestrator.evaluator.Assessment (que es el objeto en memoria).
 */
@Entity
@Table(name = "dm_assessments")
@Data
public class AssessmentRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "track_id", nullable = false)
  private Track track;

  /** Nombre del step que produjo este registro, ej. "FaceRecognitionStep". */
  /** Id de la llamada HTTP que produjo este assessment (agrupa los de un mismo chunk). */
  @Column(name = "request_id")
  private UUID requestId;

  @Column(nullable = false)
  private String step;

  /** Señales detectadas en ESTE paso, como CSV: "UNKNOWN_PERSON,WEAPON_DETECTED". */
  @Column(length = 1000)
  private String signals;

  /** Cuánto tardó el step, en milisegundos. */
  @Column(name = "duration_ms")
  private long durationMs;

  /** Tipo de media del chunk que produjo este assessment. */
  @Enumerated(EnumType.STRING)
  @Column(name = "media_type")
  private ChunkType mediaType;

  /** URL de la media subida a Cloudinary (por ahora, solo imágenes). */
  @Column(name = "media_url")
  private String mediaUrl;

  /** Identificador interno del clip; no se expone en los DTOs públicos. */
  @Column(name = "video_chunk_key", length = 100)
  private String videoChunkKey;

  @Column(name = "video_chunk_number")
  private Long videoChunkNumber;

  /** Tiempo con al menos una persona. Null = análisis insuficiente o fallido; 0 = ausencia. */
  @Column(name = "video_presence_seconds")
  private Double videoPresenceSeconds;

  /** Recepción del request, independiente de cuánto tarde el análisis. */
  @Column(name = "video_received_at")
  private LocalDateTime videoReceivedAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}
