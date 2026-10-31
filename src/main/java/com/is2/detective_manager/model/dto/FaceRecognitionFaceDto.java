package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One face detected in the original image; its closest candidate need not be recognized. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FaceRecognitionFaceDto {

  @JsonProperty("face_index")
  private Integer faceIndex;

  private FaceBox box;
  private Boolean known;
  private Double distance;

  @JsonProperty("closest_match")
  private FaceRecognitionClosestMatchDto closestMatch;

  /** Coordinates in pixels of the original image, not percentages. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FaceBox(Integer x, Integer y, Integer width, Integer height) {}
}
