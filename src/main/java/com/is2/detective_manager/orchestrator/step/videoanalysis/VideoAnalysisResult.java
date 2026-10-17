package com.is2.detective_manager.orchestrator.step.videoanalysis;

import java.util.List;

/**
 * Objective detections and tracking measurements. The received count includes malformed
 * observations discarded by the adapter so they remain distinct from an explicit absence of
 * detections.
 */
public record VideoAnalysisResult(
    VideoMetadata metadata, List<VideoObservation> observations, int receivedObservationCount) {

  public VideoAnalysisResult(VideoMetadata metadata, List<VideoObservation> observations) {
    this(metadata, observations, observations == null ? 0 : observations.size());
  }

  public record VideoMetadata(
      int width, int height, double fps, double durationSeconds, int totalFrames) {}

  public record VideoObservation(
      int frame,
      double timestampSeconds,
      long trackId,
      double confidence,
      double bboxX1Pct,
      double bboxY1Pct,
      double bboxX2Pct,
      double bboxY2Pct,
      double centerXPct,
      double centerYPct,
      double smoothCenterXPct,
      double smoothCenterYPct,
      double footXPct,
      double footYPct,
      double areaFramePct,
      Double speedDiagPctPerSecond) {}
}
