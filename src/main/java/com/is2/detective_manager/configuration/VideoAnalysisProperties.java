package com.is2.detective_manager.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Thresholds used to turn video observations into stable behavioral features. */
@Data
@Component
@ConfigurationProperties(prefix = "orchestrator.video")
public class VideoAnalysisProperties {

  private double minimumConfidence = 0.35;
  private double trackingGapToleranceSeconds = 0.75;
  private double idStitchMaximumGapSeconds = 1.0;
  private double idStitchMaximumDistancePct = 5.0;
  private double idStitchMaximumAreaChangeRatio = 0.35;
  private double prolongedPresenceSeconds = 20.0;
  private double longStopSeconds = 10.0;
  private double stationarySpeedPctPerSecond = 1.0;
  private double motionWindowSeconds = 0.75;
  private double directionMinimumDisplacementPct = 2.0;
  private double directionChangeAngleDegrees = 70.0;
  private int repeatedDirectionChanges = 3;
  private double returnMinimumExcursionPct = 10.0;
  private double returnMaximumDistancePct = 4.0;
  private double returnMinimumDurationSeconds = 3.0;
  private double approachMinimumDurationSeconds = 2.0;
  private double approachMinimumAreaIncreaseRatio = 0.25;
  private double approachMinimumLogAreaRatePerSecond = 0.04;
  private double approachMinimumSustainedRatio = 0.65;
  private double fastApproachMinimumAreaIncreaseRatio = 0.50;
  private double fastApproachMinimumLogAreaRatePerSecond = 0.12;
  private double multiplePeopleMinimumSeconds = 2.0;
  private double frameEdgeMarginPct = 8.0;
  private double videoBoundaryToleranceSeconds = 0.75;
  private double distanceSynchronizationToleranceSeconds = 0.15;
}
