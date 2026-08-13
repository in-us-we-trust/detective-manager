package com.is2.detective_manager.model.entity;

import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;

/**
 * Registro persistido del resultado de un step dentro de un track (relación 1..N).
 * Distinto de orchestrator.evaluator.Assessment (que es el objeto en memoria).
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
  @Column(nullable = false)
  private String step;

  /** Señales detectadas en ESTE paso, como CSV: "UNKNOWN_PERSON,WEAPON_DETECTED". */
  @Column(length = 1000)
  private String signals;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Severity severity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Verdict verdict;

  @Column private String message;

  /** Cuánto tardó el step, en milisegundos. */
  @Column(name = "duration_ms")
  private long durationMs;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}
