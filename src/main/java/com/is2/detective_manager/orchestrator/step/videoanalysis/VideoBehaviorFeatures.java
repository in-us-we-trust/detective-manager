package com.is2.detective_manager.orchestrator.step.videoanalysis;

import java.util.List;

/** Behavioral features derived by Detective Manager from objective video observations. */
public record VideoBehaviorFeatures(
    List<PersonFeatures> persons,
    double totalMultiplePeopleSeconds,
    double longestMultiplePeopleSeconds,
    int maximumSimultaneousPeople,
    List<RelativeDistance> relativeDistances) {

  public record PersonFeatures(
      int personIndex,
      List<Long> trackIds,
      double presenceDurationSeconds,
      List<TrajectoryPoint> trajectory,
      Direction predominantDirection,
      BoundaryEvent entry,
      BoundaryEvent exit,
      ApproachTrend approachTrend,
      double areaChangeRatio,
      double logAreaRatePerSecond,
      int directionChangeCount,
      double totalStoppedSeconds,
      double longestStopSeconds,
      boolean returnedToPreviousPosition,
      boolean prolongedPresence,
      boolean longStop,
      boolean repeatedDirectionChanges,
      boolean approachingCamera,
      boolean fastApproach,
      boolean loitering) {}

  public record TrajectoryPoint(
      double timestampSeconds,
      double centerXPct,
      double centerYPct,
      double footXPct,
      double footYPct,
      double areaFramePct,
      double confidence) {}

  public record BoundaryEvent(BoundarySector sector, BoundaryEventType type) {}

  public record RelativeDistance(
      int firstPersonIndex,
      int secondPersonIndex,
      double minimumDistancePct,
      double averageDistancePct,
      double observedTogetherSeconds) {}

  public enum Direction {
    STATIONARY,
    LEFT,
    RIGHT,
    UP,
    DOWN,
    UP_LEFT,
    UP_RIGHT,
    DOWN_LEFT,
    DOWN_RIGHT
  }

  public enum ApproachTrend {
    APPROACHING,
    MOVING_AWAY,
    STABLE
  }

  public enum BoundarySector {
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    INTERIOR
  }

  public enum BoundaryEventType {
    VIDEO_START,
    VIDEO_END,
    FRAME_EDGE,
    TRACKING_APPEARANCE,
    TRACKING_LOSS
  }
}
