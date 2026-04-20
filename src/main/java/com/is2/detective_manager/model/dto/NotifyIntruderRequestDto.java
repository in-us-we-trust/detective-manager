package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NotifyIntruderRequestDto {

  @JsonProperty("device_id")
  private String deviceId;
}
