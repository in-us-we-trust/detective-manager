package com.is2.detective_manager.model.entity;

import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/**
 * Un track: la visita al timbre que agrupa los assessments de cada step.
 * El id es el trackId que manda el timbre en su primer envío (no autogenerado).
 * El estado sobrevive entre requests: cada chunk que llega continúa desde acá.
 */
@Entity
@Table(name = "dm_tracks")
@Data
public class Track {

  @Id private UUID id; // el trackId que llega del timbre

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  /** Nombre del último step ejecutado, ej. "FaceRecognitionStep". */
  @Column(name = "current_step")
  private String currentStep;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TrackStatus status;

  /** Señales acumuladas hasta ahora, como CSV: "UNKNOWN_PERSON,WEAPON_DETECTED". */
  @Column(name = "accumulated_signals", length = 1000)
  private String accumulatedSignals;

  @Enumerated(EnumType.STRING)
  @Column(name = "final_verdict")
  private Verdict finalVerdict;

  @Enumerated(EnumType.STRING)
  @Column(name = "final_severity")
  private Severity finalSeverity;

  /** Si ya se envió la notificación de peligro (evita re-notificar el mismo track). */
  @Column(name = "notification_sent", nullable = false)
  private boolean notificationSent = false;

  @OneToMany(mappedBy = "track", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<AssessmentRecord> assessments = new ArrayList<>();

  /** Para optimistic locking a futuro (concurrencia). Dormido por ahora. */
  @Version private Long version;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}
