package com.is2.detective_manager.model.generic;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StandardResponse<T> {

  @Schema(description = "Response data")
  private T data;

  public static <T> StandardResponse<T> of(T data) {
    return new StandardResponse<>(data);
  }
}
