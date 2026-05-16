package com.is2.detective_manager.model.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EventResponseDto {

  private UUID id;

  private UUID deviceId;

  private LocalDateTime timestamp;

  private boolean intruder;

  private String imageUrl;

  private String eventType;
}
