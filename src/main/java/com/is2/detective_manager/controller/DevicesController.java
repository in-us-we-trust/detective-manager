package com.is2.detective_manager.controller;

import com.is2.detective_manager.exception.ExceptionContent;
import com.is2.detective_manager.model.dto.*;
import com.is2.detective_manager.model.generic.StandardResponse;
import com.is2.detective_manager.mapper.TrackMapper;
import com.is2.detective_manager.service.DeviceService;
import com.is2.detective_manager.service.TrackPersistenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/detective-manager/v1/devices")
public class DevicesController {

  private final DeviceService deviceService;
  private final TrackPersistenceService trackPersistenceService;
  private final TrackMapper trackMapper;

  @Autowired
  public DevicesController(
      DeviceService deviceService,
      TrackPersistenceService trackPersistenceService,
      TrackMapper trackMapper) {
    this.deviceService = deviceService;
    this.trackPersistenceService = trackPersistenceService;
    this.trackMapper = trackMapper;
  }

  @Operation(
      summary = "Register a device",
      description =
          "Creates and registers a new device. The device ID must be provided in the request body. Status is automatically set to UNCLAIMED.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Device successfully registered"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload or lastSeen is before lastBoot",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "409",
            description = "A device with this ID is already registered",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @PostMapping
  public ResponseEntity<StandardResponse<DeviceResponseDto>> createDevice(
      @Valid @RequestBody CreateDeviceRequestDto request) {
    var response = deviceService.createDevice(request);

    return ResponseEntity.status(201).body(StandardResponse.of(response));
  }

  @Operation(summary = "Get a device", description = "Returns the device with the given ID")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Device successfully retrieved"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid device UUID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "404",
            description = "Device not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @GetMapping("/{deviceId}")
  public ResponseEntity<StandardResponse<DeviceResponseDto>> getDevice(
      @PathVariable UUID deviceId) {
    return ResponseEntity.ok(StandardResponse.of(deviceService.getDevice(deviceId)));
  }

  @Operation(
      summary = "Update a device",
      description =
          "Partially updates a device. Only the fields present in the request body are updated. Fields id and uptime cannot be modified.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Device successfully updated"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload or lastSeen is before lastBoot",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "404",
            description = "Device not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @PatchMapping("/{deviceId}")
  public ResponseEntity<StandardResponse<DeviceResponseDto>> updateDevice(
      @PathVariable UUID deviceId, @Valid @RequestBody UpdateDeviceRequestDto request) {
    var response = deviceService.updateDevice(deviceId, request);

    return ResponseEntity.ok(StandardResponse.of(response));
  }

  @Operation(
      summary = "Delete a device",
      description =
          "Soft-deletes a device by setting its status to INACTIVE. The device will no longer be accessible through any endpoint.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Device successfully deleted"),
        @ApiResponse(
            responseCode = "404",
            description = "Device not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @DeleteMapping("/{deviceId}")
  public ResponseEntity<Void> deleteDevice(@PathVariable UUID deviceId) {
    deviceService.deleteDevice(deviceId);

    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Claim a device",
      description =
          "Transitions the device status from UNCLAIMED to CLAIMED. Returns 409 if the device is not in UNCLAIMED state.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Device successfully claimed"),
        @ApiResponse(
            responseCode = "404",
            description = "Device not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Device is not in UNCLAIMED state",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @PostMapping("/{deviceId}/claim")
  public ResponseEntity<StandardResponse<DeviceResponseDto>> claimDevice(
      @PathVariable UUID deviceId) {
    var response = deviceService.claimDevice(deviceId);

    return ResponseEntity.ok(StandardResponse.of(response));
  }

  @Operation(
      summary = "Unclaim a device",
      description =
          "Transitions the device status from CLAIMED back to UNCLAIMED. Returns 409 if the device is not in CLAIMED state.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Device successfully unclaimed"),
        @ApiResponse(
            responseCode = "404",
            description = "Device not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "409",
            description = "Device is not in CLAIMED state",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @PostMapping("/{deviceId}/unclaim")
  public ResponseEntity<StandardResponse<DeviceResponseDto>> unclaimDevice(
      @PathVariable UUID deviceId) {
    var response = deviceService.unclaimDevice(deviceId);

    return ResponseEntity.ok(StandardResponse.of(response));
  }

  @Operation(
      summary = "Get device tracks",
      description = "Returns all analyzed tracks (visits) associated with a specific device")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Tracks successfully retrieved"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid device UUID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @GetMapping("/{deviceId}/tracks")
  public ResponseEntity<StandardResponse<List<TrackResponseDto>>> getTracks(
      @PathVariable UUID deviceId) {
    List<TrackResponseDto> tracks =
        trackPersistenceService.findTracksByDevice(deviceId).stream()
            .map(trackMapper::toDto)
            .toList();
    return ResponseEntity.ok(StandardResponse.of(tracks));
  }
}