package com.is2.detective_manager.controller;

import com.is2.detective_manager.exception.ExceptionContent;
import com.is2.detective_manager.model.dto.EventResponseDto;
import com.is2.detective_manager.model.dto.ReportEventRequestDto;
import com.is2.detective_manager.model.dto.ReportEventResponseDto;
import com.is2.detective_manager.model.generic.StandardResponse;
import com.is2.detective_manager.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping("/api/detective-manager/v1/events")
public class EventsController {

  private final EventService eventService;

  @Autowired
  public EventsController(EventService eventService) {
    this.eventService = eventService;
  }

  @Deprecated
  @Operation(
      summary =
          "Report a security event. Deprecated - Use POST /api/detective-manager/v1/devices/{deviceId}/events",
      description =
          "Receives a security event from an IoT microcontroller and propagates it to the detection service.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Event successfully processed"),
        @ApiResponse(
            responseCode = "400",
            description = "Validation error or invalid device_id",
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
  @PostMapping
  public ResponseEntity<StandardResponse<ReportEventResponseDto>> reportEvent(
      @Parameter(description = "Device UUID", required = true) @RequestHeader("device_id")
          UUID deviceId,
      @Valid @RequestBody ReportEventRequestDto request) {

    var response = eventService.reportEvent(deviceId.toString(), request);

    return ResponseEntity.status(201).body(StandardResponse.of(response));
  }

  @Operation(
      summary = "Get events",
      description = "Returns all events or filters them by device IDs")
  @GetMapping
  public ResponseEntity<StandardResponse<List<EventResponseDto>>> getEvents(
      @RequestParam(required = false) List<UUID> deviceIds) {

    return ResponseEntity.ok(StandardResponse.of(eventService.getEventsByDevices(deviceIds)));
  }

  @Operation(summary = "Get event by ID", description = "Returns a specific event by its ID")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Event found"),
        @ApiResponse(
            responseCode = "404",
            description = "Event not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @GetMapping("/{eventId}")
  public ResponseEntity<StandardResponse<EventResponseDto>> getEvent(@PathVariable UUID eventId) {

    return ResponseEntity.ok(StandardResponse.of(eventService.getEvent(eventId)));
  }
}
