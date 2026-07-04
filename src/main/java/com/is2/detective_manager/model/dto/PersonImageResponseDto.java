package com.is2.detective_manager.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PersonImageResponseDto {

  private Long id;
  private String imageUrl;
}
