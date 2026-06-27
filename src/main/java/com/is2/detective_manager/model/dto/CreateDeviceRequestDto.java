package com.is2.detective_manager.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request body used to register a new device.")
public class CreateDeviceRequestDto {

  @NotNull(message = "id is required")
  @Schema(
      description = "Unique identifier of the device.",
      example = "9df4a749-3ec2-43b9-bc42-35d2b6b5b173",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private UUID id;

  @NotBlank(message = "serialNumber is required")
  @Schema(
      description = "Physical serial number of the device.",
      example = "ESP32-000001",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private String serialNumber;

  @Schema(description = "City where the device is installed.", example = "San Miguel")
  private String city;

  @NotNull(message = "lastSeen is required")
  @Schema(
      description = "Timestamp of the last communication received from the device.",
      example = "2026-06-27T18:30:00Z",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private Instant lastSeen;

  @NotNull(message = "lastBoot is required")
  @Schema(
      description = "Timestamp of the last device boot.",
      example = "2026-06-27T17:45:00Z",
      requiredMode = Schema.RequiredMode.REQUIRED)
  private Instant lastBoot;

  @Schema(description = "Detection threshold configured for the device.", example = "85")
  private Integer detectionThreshold;

  @Schema(description = "Physical location of the device.", example = "Front door")
  private String location;

  @Schema(description = "Firmware version currently running on the device.", example = "v1.3.7")
  private String firmwareVersion;
}
