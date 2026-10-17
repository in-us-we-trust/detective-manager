package com.is2.detective_manager.controller;

import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.generic.StandardResponse;
import com.is2.detective_manager.service.OrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/detective-manager/v1/orchestrator")
public class OrchestratorController {

  private final OrchestratorService orchestratorService;

  @Operation(
      summary = "Recibe un chunk multimedia de un evento (imagen / audio / video)",
      description = "Multipart: el archivo binario + trackId, deviceId y mediaType.")
  @PostMapping(value = "/events", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<StandardResponse<EventChunkResponseDto>> receiveEvent(
      @RequestParam("trackId") String trackId,
      @RequestParam("deviceId") String deviceId,
      @RequestParam("mediaType") ChunkType mediaType,
      @RequestParam("file") MultipartFile file)
      throws IOException {

    log.info(
        "Chunk recibido: trackId={} mediaType={} bytes={}", trackId, mediaType, file.getSize());

    EventChunkResponseDto response =
        orchestratorService.handleEvent(
            trackId, deviceId, mediaType, file.getBytes(), file.getOriginalFilename());

    return ResponseEntity.status(201).body(StandardResponse.of(response));
  }
}
