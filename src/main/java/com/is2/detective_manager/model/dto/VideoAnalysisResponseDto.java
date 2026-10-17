package com.is2.detective_manager.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

/** Response returned by {@code /api/ml-service/v1/analysis/video}. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VideoAnalysisResponseDto {

  private DataDto data;

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class DataDto {

    private MetadataDto metadata;
    private List<ObservationDto> observations;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class MetadataDto {

    private Integer width;
    private Integer height;
    private Double fps;

    @JsonProperty("duration_s")
    private Double durationSeconds;

    @JsonProperty("total_frames")
    private Integer totalFrames;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ObservationDto {

    private Integer frame;

    @JsonProperty("timestamp_s")
    private Double timestampSeconds;

    @JsonProperty("track_id")
    private Long trackId;

    private Double confidence;

    @JsonProperty("bbox_x1_pct")
    private Double bboxX1Pct;

    @JsonProperty("bbox_y1_pct")
    private Double bboxY1Pct;

    @JsonProperty("bbox_x2_pct")
    private Double bboxX2Pct;

    @JsonProperty("bbox_y2_pct")
    private Double bboxY2Pct;

    @JsonProperty("center_x_pct")
    private Double centerXPct;

    @JsonProperty("center_y_pct")
    private Double centerYPct;

    @JsonProperty("smooth_center_x_pct")
    private Double smoothCenterXPct;

    @JsonProperty("smooth_center_y_pct")
    private Double smoothCenterYPct;

    @JsonProperty("foot_x_pct")
    private Double footXPct;

    @JsonProperty("foot_y_pct")
    private Double footYPct;

    @JsonProperty("area_frame_pct")
    private Double areaFramePct;

    @JsonProperty("speed_diag_pct_s")
    private Double speedDiagPctPerSecond;
  }
}
