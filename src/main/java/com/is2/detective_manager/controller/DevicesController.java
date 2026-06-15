package com.is2.detective_manager.controller;

import com.is2.detective_manager.exception.ExceptionContent;
import com.is2.detective_manager.model.dto.CreateFaceEmbeddingRequestDto;
import com.is2.detective_manager.model.dto.EventResponseDto;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.ReportEventRequestDto;
import com.is2.detective_manager.model.dto.ReportEventResponseDto;
import com.is2.detective_manager.model.generic.StandardResponse;
import com.is2.detective_manager.service.EventService;
import com.is2.detective_manager.service.FaceEmbeddingService;
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

  private final EventService eventService;
  private final FaceEmbeddingService faceEmbeddingService;

  @Autowired
  public DevicesController(EventService eventService, FaceEmbeddingService faceEmbeddingService) {
    this.eventService = eventService;
    this.faceEmbeddingService = faceEmbeddingService;
  }

  @Operation(
      summary = "Report a device event",
      description = "Receives and processes a security event associated with a specific device")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Event successfully processed"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload or invalid UUID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "502",
            description = "Error communicating with an upstream service",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @PostMapping("/{deviceId}/events")
  public ResponseEntity<StandardResponse<ReportEventResponseDto>> reportEvent(
      @PathVariable UUID deviceId, @Valid @RequestBody ReportEventRequestDto request) {
    var response = eventService.reportEvent(deviceId.toString(), request);

    return ResponseEntity.status(201).body(StandardResponse.of(response));
  }

  @Operation(
      summary = "Get device events",
      description = "Returns all events associated with a specific device")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Events successfully retrieved"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid device UUID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @GetMapping("/{deviceId}/events")
  public ResponseEntity<StandardResponse<List<EventResponseDto>>> getEvents(
      @PathVariable UUID deviceId) {
    return ResponseEntity.ok(StandardResponse.of(eventService.getEventsByDevice(deviceId)));
  }

  @Operation(
      summary = "Register a known face",
      description = "Generates a face embedding through the ML service and stores it for a device")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Face embedding successfully created"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload or invalid UUID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "502",
            description = "Invalid response or communication error from the ML service",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @PostMapping("/{deviceId}/face-embeddings")
  public ResponseEntity<StandardResponse<FaceEmbeddingResponseDto>> createFaceEmbedding(
      @PathVariable UUID deviceId, @Valid @RequestBody CreateFaceEmbeddingRequestDto request) {
    var response = faceEmbeddingService.createFaceEmbedding(deviceId, request);

    return ResponseEntity.status(201).body(StandardResponse.of(response));
  }

  @Operation(
      summary = "Get all face embeddings for a device",
      description =
          "Returns every face embedding registered for a device, ordered by creation date")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Face embeddings successfully retrieved"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid device UUID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @GetMapping("/{deviceId}/face-embeddings")
  public ResponseEntity<StandardResponse<List<FaceEmbeddingResponseDto>>> getFaceEmbeddings(
      @PathVariable UUID deviceId) {
    return ResponseEntity.ok(StandardResponse.of(faceEmbeddingService.getFaceEmbeddings(deviceId)));
  }

  @Operation(
      summary = "Get a person's face embeddings",
      description =
          "Returns every face embedding registered for a person and device, ordered by creation date")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Face embeddings successfully retrieved"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid device UUID or person ID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @GetMapping("/{deviceId}/persons/{personId}/face-embeddings")
  public ResponseEntity<StandardResponse<List<FaceEmbeddingResponseDto>>> getFaceEmbeddings(
      @PathVariable UUID deviceId, @PathVariable Long personId) {
    return ResponseEntity.ok(
        StandardResponse.of(faceEmbeddingService.getFaceEmbeddings(deviceId, personId)));
  }
}
