package com.is2.detective_manager.model.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

@Data
public class AddPersonImagesRequestDto {

  @NotEmpty private List<String> images;
}
