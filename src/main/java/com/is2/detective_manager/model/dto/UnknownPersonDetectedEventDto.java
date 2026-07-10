package com.is2.detective_manager.model.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UnknownPersonDetectedEventDto {

  private String severity;
  private List<String> tags;
  private UUID deviceId;
  private Long personId;
  private String time;
  private String title;
  private String message;
  private String imageUrl;
}
