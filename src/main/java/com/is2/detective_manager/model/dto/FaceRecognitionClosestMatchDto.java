package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaceRecognitionClosestMatchDto {

  @JsonProperty("embedding_id")
  private Long embeddingId;

  @JsonProperty("person_id")
  private UUID personId;

  @JsonProperty("person_name")
  private String personName;
}
