package com.is2.detective_manager.model.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ClusterDto {
  @JsonProperty("embedding_ids")
  private List<Long> embeddingIds;
}
