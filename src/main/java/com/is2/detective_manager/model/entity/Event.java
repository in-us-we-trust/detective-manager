package com.is2.detective_manager.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;

@Entity
@Table(name = "events")
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
}
