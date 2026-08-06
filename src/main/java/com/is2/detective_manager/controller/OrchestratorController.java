package com.is2.detective_manager.controller;

import com.is2.detective_manager.model.dto.EventChunkRequestDto;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.generic.StandardResponse;
import com.is2.detective_manager.service.OrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/detective-manager/v1/orchestrator")
public class OrchestratorController {

  private final OrchestratorService orchestratorService;

  @Operation(
      summary = "Recibe un chunk de un evento (por ahora, una imagen)",
      description = "Primer paso del orquestador: recibe el evento y lo delega al servicio.")
  @PostMapping("/events")
  public ResponseEntity<StandardResponse<EventChunkResponseDto>> receiveEvent(
      @Valid @RequestBody EventChunkRequestDto request) {

    EventChunkResponseDto response = orchestratorService.handleEvent(request);

    return ResponseEntity.status(201).body(StandardResponse.of(response));
  }
}
