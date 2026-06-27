package com.is2.detective_manager.model.dto;

import com.is2.detective_manager.model.entity.DeviceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;

@Data
@Schema(
    description =
        "Request body used to update an existing device. Only the provided fields will be updated.")
public class UpdateDeviceRequestDto {

  @Schema(description = "Physical serial number of the device.", example = "ESP32-000001")
  private String serialNumber;

  @Schema(description = "City where the device is installed.", example = "San Miguel")
  private String city;

  @Schema(
      description = "Timestamp of the last communication received from the device.",
      example = "2026-06-27T18:30:00Z")
  private Instant lastSeen;

  @Schema(description = "Timestamp of the last device boot.", example = "2026-06-27T17:45:00Z")
  private Instant lastBoot;

  @Schema(description = "Detection threshold configured for the device.", example = "85")
  private Integer detectionThreshold;

  @Schema(description = "Physical location of the device.", example = "Main Entrance")
  private String location;

  @Schema(description = "Firmware version currently running on the device.", example = "v1.3.7")
  private String firmwareVersion;

  private DeviceStatus status;
}
