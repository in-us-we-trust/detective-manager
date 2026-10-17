package com.is2.detective_manager.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.service.OrchestratorService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrchestratorControllerContractTest {

  @Test
  void existingMultipartFieldsForwardTheGatewayFilenameAndKeepCreatedResponse() throws Exception {
    OrchestratorService service = mock(OrchestratorService.class);
    byte[] payload = {1, 2, 3};
    when(service.handleEvent("visit", "doorbell", ChunkType.VIDEO, payload, "chunk_004.mp4"))
        .thenReturn(
            EventChunkResponseDto.builder()
                .trackId("visit")
                .chunkType("VIDEO")
                .finalVerdict("CONTINUE")
                .finalSeverity("LLAMATIVO")
                .message(
                    "Atención: presencia prolongada inusual en la entrada. Revisá la situación.")
                .build());

    MockMvcBuilders.standaloneSetup(new OrchestratorController(service))
        .build()
        .perform(
            multipart("/api/detective-manager/v1/orchestrator/events")
                .file(new MockMultipartFile("file", "chunk_004.mp4", "video/mp4", payload))
                .param("trackId", "visit")
                .param("deviceId", "doorbell")
                .param("mediaType", "VIDEO"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.finalVerdict").value("CONTINUE"))
        .andExpect(jsonPath("$.data.finalSeverity").value("LLAMATIVO"));
    verify(service).handleEvent("visit", "doorbell", ChunkType.VIDEO, payload, "chunk_004.mp4");
  }
}
