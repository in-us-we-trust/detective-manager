package com.is2.detective_manager.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;

@Entity
@Table(name = "dm_events")
@Data
public class Event {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  @Column(nullable = false)
  private LocalDateTime timestamp;

  @Column(name = "is_intruder", nullable = false)
  private boolean intruder;

  @Column(name = "image_url")
  private String imageUrl;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(name = "face_known")
  private Boolean faceKnown;

  @Column(name = "face_threshold")
  private Double faceThreshold;

  @Column(name = "face_distance")
  private Double faceDistance;

  @Column(name = "face_distance_metric")
  private String faceDistanceMetric;

  @Column(name = "closest_embedding_id")
  private Long closestEmbeddingId;

  @Column(name = "closest_person_id")
  private UUID closestPersonId;

  @Column(name = "closest_person_name")
  private String closestPersonName;

  @Column(name = "face_model_name")
  private String faceModelName;

  @Column(name = "face_detector_backend")
  private String faceDetectorBackend;
}
