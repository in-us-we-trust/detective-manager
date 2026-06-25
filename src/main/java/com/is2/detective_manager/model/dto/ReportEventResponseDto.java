package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportEventResponseDto {

  private UUID eventId;
  private UUID deviceId;
  private LocalDateTime timestamp;

  @JsonProperty("isIntruder")
  private boolean isIntruder;

  private String imageUrl;
  private String eventType;
  private FaceRecognitionResultDto faceRecognition;
}
