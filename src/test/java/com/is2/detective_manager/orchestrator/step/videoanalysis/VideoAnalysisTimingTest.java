package com.is2.detective_manager.orchestrator.step.videoanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.RuleBasedDangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoMetadata;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoObservation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class VideoAnalysisTimingTest {

  @ParameterizedTest
  @MethodSource("observedDurations")
  void durationSignalsRequireEnoughObservedTimeInsteadOfAdaptingToClipLength(
      double duration, double fps, boolean prolonged, boolean longStop) {
    StepResult result = execute(video(duration, fps, false), new VideoAnalysisProperties());
    VideoBehaviorFeatures features = features(result);

    assertEquals(prolonged, result.getSignals().contains(Signal.PROLONGED_PRESENCE));
    assertEquals(longStop, result.getSignals().contains(Signal.LONG_STOP));
    assertEquals(prolonged && longStop, result.getSignals().contains(Signal.LOITERING));
    assertTrue(features.persons().get(0).presenceDurationSeconds() <= duration);
    assertTrue(features.persons().get(0).longestStopSeconds() <= duration);
  }

  @ParameterizedTest
  @ValueSource(doubles = {2, 30})
  void nominalFiveSecondDurationDoesNotSubstituteForObservedPresence(double fps) {
    VideoAnalysisResult fullClip = video(5, fps, false);
    VideoAnalysisResult briefDetection =
        new VideoAnalysisResult(
            fullClip.metadata(), fullClip.observations().subList(0, (int) (fps * 2)));

    StepResult result = execute(briefDetection, new VideoAnalysisProperties());

    assertFalse(result.getSignals().contains(Signal.PROLONGED_PRESENCE));
    assertFalse(result.getSignals().contains(Signal.LONG_STOP));
    assertFalse(result.getSignals().contains(Signal.LOITERING));
  }

  @ParameterizedTest
  @ValueSource(doubles = {2, 30})
  void approachSignalsAreReachableWithinTheGatewayDefaultFiveSecondClip(double fps) {
    StepResult result = execute(video(5, fps, true), new VideoAnalysisProperties());

    assertTrue(result.getSignals().contains(Signal.APPROACHING_CAMERA));
    assertTrue(result.getSignals().contains(Signal.FAST_APPROACH));
    assertFalse(result.getSignals().contains(Signal.PROLONGED_PRESENCE));
    assertFalse(result.getSignals().contains(Signal.LOITERING));
  }

  @Test
  void loweringThresholdsToFitFiveSecondsWouldClassifyAnOrdinaryWaitAsLoitering() {
    VideoAnalysisProperties overrides =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "orchestrator.video.prolonged-presence-seconds", "4.0",
                        "orchestrator.video.long-stop-seconds", "3.0")))
            .bind("orchestrator.video", Bindable.of(VideoAnalysisProperties.class))
            .get();

    VideoAnalysisResult ordinaryWait = video(5, 30, false);
    StepResult result = execute(ordinaryWait, overrides);

    assertEquals(
        List.of(Signal.NO_SUSPICIOUS_BEHAVIOR),
        execute(ordinaryWait, new VideoAnalysisProperties()).getSignals());
    assertTrue(result.getSignals().contains(Signal.LOITERING));
    assertEquals(
        Severity.SOSPECHOSO,
        new RuleBasedDangerEvaluator().evaluate(result.getSignals()).getSeverity());
  }

  @ParameterizedTest
  @ValueSource(doubles = {2, 30})
  void returnCanBeObservedWithinFiveSecondsWithoutCallingItLoitering(double fps) {
    VideoAnalysisResult trajectory =
        withTrajectory(
            video(5, fps, false),
            time -> time <= 2.5 ? 20 + 40 * time / 2.5 : 60 - 40 * (time - 2.5) / 2.5);

    StepResult result = execute(trajectory, new VideoAnalysisProperties());

    assertTrue(result.getSignals().contains(Signal.RETURNED_TO_PREVIOUS_POSITION));
    assertFalse(result.getSignals().contains(Signal.PROLONGED_PRESENCE));
    assertFalse(result.getSignals().contains(Signal.LOITERING));
  }

  @ParameterizedTest
  @ValueSource(doubles = {2, 30})
  void multiplePeopleAreReachableInFiveSecondsButABriefOverlapDoesNotQualify(double fps) {
    VideoAnalysisResult clip = video(5, fps, false);
    List<VideoObservation> sustained = new ArrayList<>(clip.observations());
    sustained.addAll(
        clip.observations().stream().map(observation -> atX(observation, 75, 72)).toList());
    List<VideoObservation> brief = new ArrayList<>(clip.observations());
    brief.addAll(
        clip.observations().stream()
            .filter(observation -> observation.timestampSeconds() < 1)
            .map(observation -> atX(observation, 75, 72))
            .toList());

    assertTrue(
        execute(new VideoAnalysisResult(clip.metadata(), sustained), new VideoAnalysisProperties())
            .getSignals()
            .contains(Signal.MULTIPLE_PEOPLE));
    assertFalse(
        execute(new VideoAnalysisResult(clip.metadata(), brief), new VideoAnalysisProperties())
            .getSignals()
            .contains(Signal.MULTIPLE_PEOPLE));
  }

  @ParameterizedTest
  @ValueSource(doubles = {2, 30})
  void threeSustainedDirectionChangesNeedMoreTimeThanTheDefaultFiveSecondClip(double fps) {
    for (double duration : List.of(5.0, 10.0)) {
      VideoAnalysisResult trajectory =
          withTrajectory(
              video(duration, fps, false),
              time -> {
                double section = time / (duration / 4);
                int leg = (int) section;
                double progress = section - leg;
                return leg % 2 == 0 ? 20 + 50 * progress : 70 - 50 * progress;
              });

      StepResult result = execute(trajectory, new VideoAnalysisProperties());

      assertEquals(duration == 10, result.getSignals().contains(Signal.REPEATED_DIRECTION_CHANGES));
    }
  }

  @Test
  void perPersonSignalsRemainLocalToTheClipEvenWithTheSameTrackId() {
    VideoAnalysisProperties properties = new VideoAnalysisProperties();
    VideoAnalysisStep step = step(video(5, 30, false), properties);
    StepResult first = step.execute(context(Map.of()));
    StepResult second = step.execute(context(first.getData()));

    assertEquals(List.of(Signal.NO_SUSPICIOUS_BEHAVIOR), first.getSignals());
    assertEquals(List.of(Signal.NO_SUSPICIOUS_BEHAVIOR), second.getSignals());
    assertTrue(features(second).persons().get(0).presenceDurationSeconds() < 5);
    assertEquals(5, features(second).observedPresenceSeconds(), 1e-6);
  }

  @ParameterizedTest
  @ValueSource(doubles = {2, 30})
  void visitTimeCountsObservedFramesWithoutMultiplyingSimultaneousPeople(double fps) {
    VideoAnalysisResult clip = video(5, fps, false);
    List<VideoObservation> observations = new ArrayList<>(clip.observations());
    observations.addAll(clip.observations().stream().map(o -> atX(o, 70, 82)).toList());

    VideoBehaviorFeatures features =
        features(
            execute(
                new VideoAnalysisResult(clip.metadata(), observations),
                new VideoAnalysisProperties()));

    assertEquals(5, features.observedPresenceSeconds(), 1e-6);
    assertEquals(2, features.persons().size());
  }

  @Test
  void visitTimeDoesNotFillUnobservedGapsOrExtendPastTheClip() {
    VideoAnalysisResult clip = video(5, 2, false);
    VideoAnalysisResult sparse =
        new VideoAnalysisResult(
            clip.metadata(),
            List.of(
                clip.observations().get(0),
                clip.observations().get(4),
                clip.observations().get(9),
                clip.observations().get(9)));

    assertEquals(
        1.5,
        features(execute(sparse, new VideoAnalysisProperties())).observedPresenceSeconds(),
        1e-6);
    VideoAnalysisResult shorter =
        new VideoAnalysisResult(new VideoMetadata(1920, 1080, 2, 4.75, 10), clip.observations());
    assertEquals(
        4.75,
        features(execute(shorter, new VideoAnalysisProperties())).observedPresenceSeconds(),
        1e-6);
  }

  private StepResult execute(VideoAnalysisResult analysis, VideoAnalysisProperties properties) {
    return step(analysis, properties).execute(context(Map.of()));
  }

  private VideoAnalysisStep step(VideoAnalysisResult analysis, VideoAnalysisProperties properties) {
    return new VideoAnalysisStep(
        ignored -> analysis, new VideoFeatureCalculator(properties), properties);
  }

  private StepContext context(Map<String, Object> accumulated) {
    return StepContext.builder()
        .trackId("123e4567-e89b-12d3-a456-426614174000")
        .mediaType(ChunkType.VIDEO)
        .payload(new byte[] {1})
        .accumulated(accumulated)
        .build();
  }

  private VideoBehaviorFeatures features(StepResult result) {
    return (VideoBehaviorFeatures) result.getData().get(VideoAnalysisStep.FEATURES_DATA_KEY);
  }

  private VideoAnalysisResult video(double duration, double fps, boolean approaching) {
    int totalFrames = (int) (duration * fps);
    List<VideoObservation> observations =
        IntStream.range(0, totalFrames)
            .mapToObj(
                frame -> {
                  double time = frame / fps;
                  double y = approaching ? 45 + time : 50;
                  return new VideoObservation(
                      frame,
                      time,
                      71,
                      0.95,
                      40,
                      y - 20,
                      60,
                      y + 20,
                      50,
                      y,
                      50,
                      y,
                      50,
                      y + 20,
                      approaching ? 5 * Math.exp(0.16 * time) : 10,
                      approaching ? 4.0 : 0.1);
                })
            .toList();
    return new VideoAnalysisResult(
        new VideoMetadata(1920, 1080, fps, duration, totalFrames), observations);
  }

  private VideoAnalysisResult withTrajectory(VideoAnalysisResult clip, DoubleUnaryOperator x) {
    return new VideoAnalysisResult(
        clip.metadata(),
        clip.observations().stream()
            .map(
                observation ->
                    atX(observation, x.applyAsDouble(observation.timestampSeconds()), 71))
            .toList());
  }

  private VideoObservation atX(VideoObservation observation, double x, long trackId) {
    return new VideoObservation(
        observation.frame(),
        observation.timestampSeconds(),
        trackId,
        observation.confidence(),
        x - 5,
        observation.bboxY1Pct(),
        x + 5,
        observation.bboxY2Pct(),
        x,
        observation.centerYPct(),
        x,
        observation.smoothCenterYPct(),
        x,
        observation.footYPct(),
        observation.areaFramePct(),
        observation.speedDiagPctPerSecond());
  }

  private static Stream<Arguments> observedDurations() {
    return Stream.of(2.0, 5.0, 10.0, 11.0, 20.0, 21.0, 25.0)
        .flatMap(
            duration ->
                Stream.of(2.0, 30.0)
                    .map(fps -> Arguments.of(duration, fps, duration >= 21, duration >= 11)));
  }
}
