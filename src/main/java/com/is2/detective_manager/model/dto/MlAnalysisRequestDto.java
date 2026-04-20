package com.is2.detective_manager.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MlAnalysisRequestDto {

  private String image;
  private String type;
}
