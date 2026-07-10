package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlClusteringResponseDto {

  private List<ClusterDto>  data;
}
