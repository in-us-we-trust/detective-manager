package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
public class ClusterDto {
  @JsonProperty("embedding_ids")
  private List<Long> embeddingIds;
}
