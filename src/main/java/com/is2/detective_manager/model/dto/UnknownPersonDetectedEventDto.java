package com.is2.detective_manager.model.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UnknownPersonDetectedEventDto {

  private UUID deviceId;
  private UUID personId;
  private String time;
  private int distinctDays;
  private List<String> imageUrls;
}
