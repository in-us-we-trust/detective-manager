package com.is2.detective_manager.model.dto;

import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class PersonResponseDto {

  private Long id;
  private UUID deviceId;
  private String name;
  private String role;
  private List<PersonImageResponseDto> images;
}
