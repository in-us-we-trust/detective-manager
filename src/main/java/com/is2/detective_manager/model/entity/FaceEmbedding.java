package com.is2.detective_manager.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "dm_face_embeddings",
    indexes = {@Index(name = "idx_dm_face_embeddings_person", columnList = "person_id")})
@Data
public class FaceEmbedding {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "person_id", nullable = true)
  private Person person;

  @Column(name = "device_id")
  private UUID deviceId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = true)
  private FaceEmbeddingStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private List<Double> embedding;

  @Column(name = "model_name", nullable = false)
  private String modelName;

  @Column(name = "detector_backend", nullable = false)
  private String detectorBackend;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "image_url")
  private String imageUrl;
}
