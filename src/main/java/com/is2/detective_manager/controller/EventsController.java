package com.is2.detective_manager.controller;

import com.is2.detective_manager.exception.ExceptionContent;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/detective-manager/v1")
public class EventsController {

  private final EventService eventService;

  @Autowired
  public EventsController(EventService eventService) {
    this.eventService = eventService;
  }

  @Operation(
      summary = "Report a security event",
      description =
          "Receives a security event from an IoT microcontroller and propagates it to the detection service")
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
  @PostMapping("/events")
  public ResponseEntity<StandardResponse<ReportEventResponseDto>> reportEvent(
      @Parameter(description = "Device UUID", required = true) @RequestHeader("device_id")
          String deviceId,
      @Valid @RequestBody ReportEventRequestDto request) {
    var response = eventService.reportEvent(deviceId, request);
    return ResponseEntity.status(201).body(StandardResponse.of(response));
  }
}
