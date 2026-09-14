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

    private int width;
    private int height;
    private double fps;

    @JsonProperty("duration_s")
    private double durationSeconds;

    @JsonProperty("total_frames")
    private int totalFrames;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ObservationDto {

    private int frame;

    @JsonProperty("timestamp_s")
    private double timestampSeconds;

    @JsonProperty("track_id")
    private Long trackId;

    private double confidence;

    @JsonProperty("bbox_x1_pct")
    private double bboxX1Pct;

    @JsonProperty("bbox_y1_pct")
    private double bboxY1Pct;

    @JsonProperty("bbox_x2_pct")
    private double bboxX2Pct;

    @JsonProperty("bbox_y2_pct")
    private double bboxY2Pct;

    @JsonProperty("center_x_pct")
    private double centerXPct;

    @JsonProperty("center_y_pct")
    private double centerYPct;

    @JsonProperty("smooth_center_x_pct")
    private Double smoothCenterXPct;

    @JsonProperty("smooth_center_y_pct")
    private Double smoothCenterYPct;

    @JsonProperty("foot_x_pct")
    private double footXPct;

    @JsonProperty("foot_y_pct")
    private double footYPct;

    @JsonProperty("area_frame_pct")
    private double areaFramePct;

    @JsonProperty("speed_diag_pct_s")
    private Double speedDiagPctPerSecond;
  }
}
