package com.is2.detective_manager.orchestrator.step.videoanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoMetadata;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoObservation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoAnalysisStepTest {

  private final VideoAnalysisProperties properties = new VideoAnalysisProperties();
  private final VideoFeatureCalculator calculator = new VideoFeatureCalculator(properties);

  @Test
  void execute_generatesStableBehaviorSignalsAndExposesFeatures() {
    List<VideoObservation> observations = new ArrayList<>();

    // Person 1 remains almost still for 25 seconds.
    for (int sample = 0; sample <= 50; sample++) {
      double timestamp = sample * 0.5;
      observations.add(observation(sample, timestamp, 1, 50, 50, 12, 0.1, 0.95));
    }

    // Person 2 approaches steadily and quickly for six seconds.
    for (int sample = 0; sample <= 12; sample++) {
      double timestamp = sample * 0.5;
      double area = 5 * Math.exp(0.16 * timestamp);
      observations.add(
          observation(100 + sample, timestamp, 2, 65, 45 + timestamp, area, 4.0, 0.95));
    }

    StepResult result = execute(observations, 25);

    assertTrue(result.getSignals().contains(Signal.PROLONGED_PRESENCE));
    assertTrue(result.getSignals().contains(Signal.LONG_STOP));
    assertFalse(result.getSignals().contains(Signal.LOITERING));
    assertTrue(result.getSignals().contains(Signal.APPROACHING_CAMERA));
    assertTrue(result.getSignals().contains(Signal.FAST_APPROACH));
    assertFalse(result.getSignals().contains(Signal.NO_SUSPICIOUS_BEHAVIOR));
    assertTrue(
        result.getData().get(VideoAnalysisStep.FEATURES_DATA_KEY) instanceof VideoBehaviorFeatures);
  }

  @Test
  void execute_detectsSustainedDirectionChangesAndReturn() {
    List<VideoObservation> observations = new ArrayList<>();
    double[][] sections = {{0, 2, 20, 40}, {2.5, 4.5, 40, 20}, {5, 7, 20, 40}, {7.5, 9.5, 40, 20}};
    int frame = 0;
    for (double[] section : sections) {
      for (double timestamp = section[0]; timestamp <= section[1]; timestamp += 0.5) {
        double progress = (timestamp - section[0]) / (section[1] - section[0]);
        double x = section[2] + (section[3] - section[2]) * progress;
        observations.add(observation(frame++, timestamp, 1, x, 50, 10, 8, 0.95));
      }
    }

    StepResult result = execute(observations, 10);

    assertTrue(result.getSignals().contains(Signal.REPEATED_DIRECTION_CHANGES));
    assertTrue(result.getSignals().contains(Signal.RETURNED_TO_PREVIOUS_POSITION));
    assertFalse(result.getSignals().contains(Signal.LOITERING));
  }

  @Test
  void execute_reusesMultiplePeopleOnlyForSustainedOverlap() {
    List<VideoObservation> sustained = new ArrayList<>();
    for (int sample = 0; sample <= 10; sample++) {
      double timestamp = sample * 0.5;
      sustained.add(observation(sample, timestamp, 1, 25, 50, 10, 0.1, 0.95));
      if (timestamp >= 1 && timestamp <= 4) {
        sustained.add(observation(100 + sample, timestamp, 2, 75, 50, 10, 0.1, 0.95));
      }
    }

    assertTrue(execute(sustained, 5).getSignals().contains(Signal.MULTIPLE_PEOPLE));

    List<VideoObservation> brief =
        List.of(
            observation(0, 0, 1, 25, 50, 10, 0.1, 0.95),
            observation(1, 0.5, 1, 25, 50, 10, 0.1, 0.95),
            observation(2, 0, 2, 75, 50, 10, 0.1, 0.95),
            observation(3, 0.5, 2, 75, 50, 10, 0.1, 0.95));

    assertFalse(execute(brief, 1).getSignals().contains(Signal.MULTIPLE_PEOPLE));
  }

  @Test
  void execute_ignoresLowConfidenceAndShortJitter() {
    List<VideoObservation> observations = new ArrayList<>();
    for (int sample = 0; sample <= 10; sample++) {
      double timestamp = sample * 0.5;
      double jitter = sample % 2 == 0 ? 0.2 : -0.2;
      observations.add(observation(sample, timestamp, 1, 50 + jitter, 50, 10, 0.2, 0.95));
      observations.add(
          observation(100 + sample, timestamp, 2, 20 + sample * 5, 50, 5 + sample * 3, 12, 0.10));
    }

    StepResult result = execute(observations, 5);
    Set<Signal> signals = Set.copyOf(result.getSignals());

    assertEquals(AnalysisQuality.VALID, result.getQuality());
    assertTrue(signals.contains(Signal.NO_SUSPICIOUS_BEHAVIOR));
    assertFalse(signals.contains(Signal.PROLONGED_PRESENCE));
    assertFalse(signals.contains(Signal.LONG_STOP));
    assertFalse(signals.contains(Signal.REPEATED_DIRECTION_CHANGES));
    assertFalse(signals.contains(Signal.APPROACHING_CAMERA));
    assertFalse(signals.contains(Signal.FAST_APPROACH));
    assertFalse(signals.contains(Signal.LOITERING));
  }

  @Test
  void execute_toleratesTrackingGapAndConservativelyStitchesChangedId() {
    List<VideoObservation> observations = new ArrayList<>();
    for (int sample = 0; sample <= 19; sample++) {
      double timestamp = sample * 0.5;
      observations.add(observation(sample, timestamp, 10, 50, 50, 10, 0.1, 0.95));
    }
    for (int sample = 20; sample <= 40; sample++) {
      double timestamp = sample * 0.5;
      observations.add(observation(sample, timestamp, 99, 50.5, 50, 10.2, 0.1, 0.95));
    }

    StepResult result = execute(observations, 20);
    VideoBehaviorFeatures features =
        (VideoBehaviorFeatures) result.getData().get(VideoAnalysisStep.FEATURES_DATA_KEY);

    assertTrue(result.getSignals().contains(Signal.PROLONGED_PRESENCE));
    assertEquals(1, features.persons().size());
    assertEquals(Set.of(10L, 99L), Set.copyOf(features.persons().get(0).trackIds()));
  }

  @Test
  void calculate_keepsRelativeDistanceAsFeatureWithoutCreatingSignal() {
    List<VideoObservation> observations = new ArrayList<>();
    for (int sample = 0; sample <= 6; sample++) {
      double timestamp = sample * 0.5;
      observations.add(observation(sample, timestamp, 1, 40, 50, 10, 0.1, 0.95));
      observations.add(observation(100 + sample, timestamp, 2, 50, 50, 10, 0.1, 0.95));
    }

    StepResult result = execute(observations, 3);
    VideoBehaviorFeatures features =
        (VideoBehaviorFeatures) result.getData().get(VideoAnalysisStep.FEATURES_DATA_KEY);

    assertEquals(1, features.relativeDistances().size());
    assertTrue(features.relativeDistances().get(0).minimumDistancePct() > 0);
  }

  @Test
  void execute_emitsNoSuspiciousBehaviorOnlyWithoutSuspiciousFindings() {
    StepResult ordinaryPresence = execute(List.of(observation(0, 0, 1, 50, 50, 12, 0.1, 0.95)), 5);
    assertEquals(List.of(Signal.NO_SUSPICIOUS_BEHAVIOR), ordinaryPresence.getSignals());
    assertEquals(AnalysisQuality.VALID, ordinaryPresence.getQuality());

    List<VideoObservation> still = new ArrayList<>();
    for (int sample = 0; sample <= 50; sample++) {
      still.add(observation(sample, sample * 0.5, 1, 50, 50, 12, 0.1, 0.95));
    }

    assertFalse(execute(still, 25).getSignals().contains(Signal.NO_SUSPICIOUS_BEHAVIOR));
  }

  @ParameterizedTest
  @ValueSource(doubles = {0.08, 0.16})
  void execute_preservesInformationalApproachesAndReportsNoSuspiciousBehavior(double areaRate) {
    StepResult result = execute(approachObservations(areaRate), 6);

    assertEquals(AnalysisQuality.VALID, result.getQuality());
    assertEquals(
        areaRate > 0.12
            ? Set.of(Signal.APPROACHING_CAMERA, Signal.FAST_APPROACH, Signal.NO_SUSPICIOUS_BEHAVIOR)
            : Set.of(Signal.APPROACHING_CAMERA, Signal.NO_SUSPICIOUS_BEHAVIOR),
        Set.copyOf(result.getSignals()));
    VideoBehaviorFeatures features =
        (VideoBehaviorFeatures) result.getData().get(VideoAnalysisStep.FEATURES_DATA_KEY);
    assertTrue(features.persons().get(0).approachingCamera());
    assertEquals(areaRate > 0.12, features.persons().get(0).fastApproach());
  }

  @Test
  void execute_preservesInformationalApproachesWithoutReassuranceWhenAnalysisIsIncomplete() {
    List<VideoObservation> observations = approachObservations(0.16);
    StepResult result =
        execute(
            new VideoAnalysisResult(
                new VideoMetadata(1920, 1080, 2, 6, 12), observations, observations.size() + 1));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertEquals(
        Set.of(Signal.APPROACHING_CAMERA, Signal.FAST_APPROACH), Set.copyOf(result.getSignals()));
    assertFalse(result.getMessage().isBlank());
  }

  @Test
  void execute_reportsNoPersonsWithoutClaimingBehaviorWasEvaluated() {
    StepResult result = execute(List.of(), 5);

    assertEquals(AnalysisQuality.VALID, result.getQuality());
    assertTrue(result.getSignals().isEmpty());
    assertEquals("No se detectaron personas en el video", result.getMessage());
  }

  @Test
  void execute_marksOnlyLowConfidenceObservationsAsInsufficient() {
    StepResult result = execute(List.of(observation(0, 0, 1, 50, 50, 12, 0.1, 0.1)), 5);

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertTrue(result.getSignals().isEmpty());
    assertEquals(
        "No hay observaciones utilizables para evaluar el comportamiento en el video",
        result.getMessage());
  }

  @Test
  void execute_marksDiscardedMalformedObservationsAsInsufficient() {
    StepResult result =
        execute(new VideoAnalysisResult(new VideoMetadata(1920, 1080, 2, 5, 10), List.of(), 2));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertTrue(result.getSignals().isEmpty());
    assertFalse(result.getMessage().contains("No se detectaron personas"));
  }

  @Test
  void execute_marksPartiallyMalformedObservationsInsufficientWithoutReassurance() {
    List<VideoObservation> usable = List.of(observation(0, 0, 1, 50, 50, 12, 0.1, 0.95));

    StepResult result =
        execute(new VideoAnalysisResult(new VideoMetadata(1920, 1080, 2, 5, 10), usable, 4));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertTrue(result.getSignals().isEmpty());
    assertEquals(
        "Algunas observaciones del video no pudieron utilizarse para evaluar el comportamiento",
        result.getMessage());
  }

  @Test
  void execute_preservesBehaviorEvidenceWhenSomeObservationsWereDiscarded() {
    List<VideoObservation> observations = new ArrayList<>();
    for (int sample = 0; sample <= 50; sample++) {
      observations.add(observation(sample, sample * 0.5, 1, 50, 50, 12, 0.1, 0.95));
    }
    observations.add(observation(51, 0, 2, 50, 50, 12, 0.1, 0.1));
    StepResult result =
        execute(
            new VideoAnalysisResult(
                new VideoMetadata(1920, 1080, 2, 25, 50), observations, observations.size() + 1));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertTrue(result.getSignals().contains(Signal.PROLONGED_PRESENCE));
    assertFalse(result.getSignals().contains(Signal.LOITERING));
    assertTrue(result.getSignals().contains(Signal.LONG_STOP));
    assertFalse(result.getSignals().contains(Signal.NO_SUSPICIOUS_BEHAVIOR));
  }

  @Test
  void execute_rejectsAnUnavailableAnalyzerResult() {
    assertThrows(DetectiveException.class, () -> execute((VideoAnalysisResult) null));
  }

  private StepResult execute(List<VideoObservation> observations, double durationSeconds) {
    VideoAnalysisResult analysis =
        new VideoAnalysisResult(
            new VideoMetadata(1920, 1080, 2, durationSeconds, (int) (durationSeconds * 2)),
            observations);
    return execute(analysis);
  }

  private List<VideoObservation> approachObservations(double areaRate) {
    List<VideoObservation> observations = new ArrayList<>();
    for (int sample = 0; sample <= 12; sample++) {
      double timestamp = sample * 0.5;
      observations.add(
          observation(
              sample,
              timestamp,
              1,
              50,
              45 + timestamp,
              5 * Math.exp(areaRate * timestamp),
              4.0,
              0.95));
    }
    return observations;
  }

  private StepResult execute(VideoAnalysisResult analysis) {
    VideoAnalysisStep step = new VideoAnalysisStep(ignored -> analysis, calculator, properties);
    return step.execute(
        StepContext.builder()
            .trackId(UUID.randomUUID().toString())
            .deviceId(UUID.randomUUID())
            .mediaType(ChunkType.VIDEO)
            .payload(new byte[] {1})
            .accumulated(Map.of())
            .build());
  }

  private VideoObservation observation(
      int frame,
      double timestamp,
      long trackId,
      double centerX,
      double centerY,
      double area,
      double speed,
      double confidence) {
    return new VideoObservation(
        frame,
        timestamp,
        trackId,
        confidence,
        centerX - 5,
        centerY - 20,
        centerX + 5,
        centerY + 20,
        centerX,
        centerY,
        centerX,
        centerY,
        centerX,
        centerY + 20,
        area,
        speed);
  }
}
