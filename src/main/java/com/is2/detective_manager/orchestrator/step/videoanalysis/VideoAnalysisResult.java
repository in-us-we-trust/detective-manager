package com.is2.detective_manager.orchestrator.step.videoanalysis;

import java.util.List;

/** Objective detections and tracking measurements returned by the video ML model. */
public record VideoAnalysisResult(VideoMetadata metadata, List<VideoObservation> observations) {

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
