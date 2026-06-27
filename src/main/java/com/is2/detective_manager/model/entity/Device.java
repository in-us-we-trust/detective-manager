package com.is2.detective_manager.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Entity
@Table(name = "dm_devices")
@Data
public class Device {

  @Id private UUID id;

  @Column(name = "serial_number", nullable = false)
  private String serialNumber;

  @Column private String city;

  @Column(name = "last_seen", nullable = false)
  private Instant lastSeen;

  @Column(name = "last_boot", nullable = false)
  private Instant lastBoot;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DeviceStatus status;

  @Column(nullable = false)
  private Long uptime;

  @Column(name = "detection_threshold", nullable = false)
  private Integer detectionThreshold;

  @Column private String location;

  @Column(name = "firmware_version")
  private String firmwareVersion;
}
