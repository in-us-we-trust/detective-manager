package com.is2.detective_manager.controller;

import com.is2.detective_manager.model.dto.BatchResponseDto;
import com.is2.detective_manager.model.generic.StandardResponse;
import com.is2.detective_manager.service.BatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/detective-manager/v1/batch")
public class BatchController {

  private final BatchService batchService;

  @Autowired
  public BatchController(BatchService batchService) {
    this.batchService = batchService;
  }

  @Operation(
      summary = "Run unknown persons batch",
      description =
          "Executes the full batch process: removes expired orphan embeddings, clusters the remaining ones via ML Service, and creates UNKNOWN persons for clusters that meet the minimum distinct-days threshold.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Batch executed successfully"),
        @ApiResponse(responseCode = "502", description = "Error communicating with ML service")
      })
  @PostMapping("/persons")
  public ResponseEntity<StandardResponse<BatchResponseDto>> runBatch() {
    var deleted = batchService.deleteOldPersonInformation();
    var result = batchService.runUnknownPersonsBatch(deleted);
    return ResponseEntity.ok(StandardResponse.of(result));
  }
}
