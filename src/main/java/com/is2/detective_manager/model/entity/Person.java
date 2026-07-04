package com.is2.detective_manager.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;

@Entity
@Table(
    name = "dm_persons",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_dm_persons_device_normalized_name",
          columnNames = {"device_id", "normalized_name"})
    })
@Data
public class Person {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "device_id", nullable = false)
  private UUID deviceId;

  @Column(nullable = false)
  private String name;

  @Column(name = "normalized_name", nullable = false)
  private String normalizedName;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column() private String role;
}
