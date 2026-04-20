package com.is2.detective_manager.exception;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExceptionContent {

  @Schema(description = "HTTP status code", example = "400")
  private int status;

  @Schema(description = "Error title", example = "Bad Request")
  private String title;

  @Schema(description = "Detailed description of the error", example = "The device_id header must be a valid UUID.")
  private String detail;

  @Schema(description = "Path where the error occurred", example = "/api/detective-manager/v1/events")
  private String instance;

  @Schema(description = "Error type", example = "about:blank")
  private String type;
}
