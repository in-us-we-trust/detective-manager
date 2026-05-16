package com.is2.detective_manager.controller;

import com.is2.detective_manager.exception.ExceptionContent;
import com.is2.detective_manager.model.dto.EventResponseDto;
import com.is2.detective_manager.model.dto.ReportEventRequestDto;
import com.is2.detective_manager.model.dto.ReportEventResponseDto;
import com.is2.detective_manager.model.generic.StandardResponse;
import com.is2.detective_manager.service.EventService;
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

  @Autowired
  public DevicesController(EventService eventService) {
    this.eventService = eventService;
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
}
