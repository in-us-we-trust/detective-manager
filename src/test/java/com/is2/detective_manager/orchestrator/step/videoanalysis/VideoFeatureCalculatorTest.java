package com.is2.detective_manager.orchestrator.step.videoanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoMetadata;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoObservation;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures.PersonFeatures;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class VideoFeatureCalculatorTest {

  private static final int FPS = 30;

  private final VideoFeatureCalculator calculator =
      new VideoFeatureCalculator(new VideoAnalysisProperties());

  @Test
  void thirtySecondWaitKeepsPresenceAndStopEvidenceWithoutLoitering() {
    VideoBehaviorFeatures features = calculator.calculate(stationaryAnalysis(30));
    PersonFeatures person = features.persons().get(0);

    assertEquals(30, features.observedPresenceSeconds(), 1e-6);
    assertTrue(person.presenceDurationSeconds() > 29.9);
    assertTrue(person.prolongedPresence());
    assertTrue(person.longStop());
    assertFalse(person.repeatedDirectionChanges());
    assertFalse(person.returnedToPreviousPosition());
    assertFalse(person.loitering());
  }

  @Test
  void thirtySecondZigzagActivatesLoiteringWithoutReturningOrStopping() {
    VideoAnalysisResult analysis =
        analysis(
            0,
            30,
            timestamp -> {
              int section = (int) (timestamp / 6);
              double progress = timestamp % 6;
              return 20 + 4 * (section % 2 == 0 ? progress : 6 - progress);
            },
            timestamp -> 15 + 2 * timestamp,
            Math.hypot(4, 2) / Math.sqrt(2));
    PersonFeatures person = calculator.calculate(analysis).persons().get(0);

    assertTrue(person.prolongedPresence());
    assertTrue(person.repeatedDirectionChanges());
    assertFalse(person.returnedToPreviousPosition());
    assertFalse(person.longStop());
    assertTrue(person.loitering());
  }

  @Test
  void sixFiveSecondWaitsDoNotAccumulatePersonFeaturesAcrossCalculatorCalls() {
    for (int chunk = 0; chunk < 6; chunk++) {
      VideoBehaviorFeatures features = calculator.calculate(stationaryAnalysis(5));
      PersonFeatures person = features.persons().get(0);

      assertEquals(5, features.observedPresenceSeconds(), 1e-6);
      assertTrue(person.presenceDurationSeconds() < 5);
      assertFalse(person.prolongedPresence());
      assertFalse(person.longStop());
      assertFalse(person.repeatedDirectionChanges());
      assertFalse(person.returnedToPreviousPosition());
      assertFalse(person.loitering());
    }
  }

  @Test
  void splittingTheSameReturnIntoFiveSecondClipsLosesTheCrossClipTrajectory() {
    PersonFeatures fullVideo = calculator.calculate(returnAnalysis(0, 30)).persons().get(0);

    assertTrue(fullVideo.prolongedPresence());
    assertTrue(fullVideo.returnedToPreviousPosition());
    assertFalse(fullVideo.repeatedDirectionChanges());
    assertFalse(fullVideo.longStop());
    assertFalse(fullVideo.loitering());

    for (int chunk = 0; chunk < 6; chunk++) {
      PersonFeatures person = calculator.calculate(returnAnalysis(chunk * 5, 5)).persons().get(0);

      // Each local trajectory is one-way; neither IDs nor movement history cross clip boundaries.
      assertFalse(person.returnedToPreviousPosition());
      assertFalse(person.repeatedDirectionChanges());
      assertFalse(person.prolongedPresence());
      assertFalse(person.longStop());
      assertFalse(person.loitering());
    }
  }

  private VideoAnalysisResult stationaryAnalysis(int durationSeconds) {
    return analysis(0, durationSeconds, timestamp -> 50, timestamp -> 50, 0);
  }

  private VideoAnalysisResult returnAnalysis(int startSeconds, int durationSeconds) {
    return analysis(
        startSeconds,
        durationSeconds,
        timestamp -> 20 + (50.0 / 15) * (timestamp < 15 ? timestamp : 30 - timestamp),
        timestamp -> 50,
        (50.0 / 15) / Math.sqrt(2));
  }

  private VideoAnalysisResult analysis(
      int startSeconds,
      int durationSeconds,
      DoubleUnaryOperator xAt,
      DoubleUnaryOperator yAt,
      double speed) {
    int totalFrames = durationSeconds * FPS;
    List<VideoObservation> observations = new ArrayList<>();
    for (int frame = 0; frame < totalFrames; frame++) {
      double timestamp = (double) frame / FPS;
      double globalTimestamp = startSeconds + timestamp;
      double x = xAt.applyAsDouble(globalTimestamp);
      double y = yAt.applyAsDouble(globalTimestamp);
      observations.add(
          new VideoObservation(
              frame, timestamp, 1, 0.95, x - 5, y - 10, x + 5, y + 10, x, y, x, y, x, y + 10, 12,
              speed));
    }
    return new VideoAnalysisResult(
        new VideoMetadata(1920, 1080, FPS, durationSeconds, totalFrames), observations);
  }
}
