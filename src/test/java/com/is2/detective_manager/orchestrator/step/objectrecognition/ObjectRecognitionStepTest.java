package com.is2.detective_manager.orchestrator.step.objectrecognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ObjectRecognitionStepTest {

  @Test
  void execute_validEmptyDetectionMeansNoThreatObjectsWereDetected() {
    StepResult result = execute(new ObjectDetectionResult(List.of(), Map.of()));

    assertEquals(AnalysisQuality.VALID, result.getQuality());
    assertEquals(List.of(Signal.NO_THREAT_OBJECT), result.getSignals());
  }

  @Test
  void execute_partialDetectionDoesNotClaimThereAreNoThreatObjects() {
    StepResult result =
        execute(new ObjectDetectionResult(List.of(), Map.of(), AnalysisQuality.INSUFFICIENT));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertTrue(result.getSignals().isEmpty());
  }

  @Test
  void execute_partialDetectionRetainsValidThreatFinding() {
    StepResult result =
        execute(
            new ObjectDetectionResult(
                List.of(new DetectedObject("Firearm", 0.9)),
                Map.of("Firearm", 1),
                AnalysisQuality.INSUFFICIENT));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertEquals(List.of(Signal.WEAPON_DETECTED), result.getSignals());
    assertFalse(result.getSignals().contains(Signal.NO_THREAT_OBJECT));
  }

  @ParameterizedTest
  @MethodSource("contextObjects")
  void execute_contextObjectsAboveThresholdAreInformative(String label, Signal expectedSignal) {
    StepResult result =
        execute(
            new ObjectDetectionResult(List.of(new DetectedObject(label, 0.31)), Map.of(label, 1)));

    assertEquals(AnalysisQuality.VALID, result.getQuality());
    assertEquals(Set.of(expectedSignal, Signal.NO_THREAT_OBJECT), Set.copyOf(result.getSignals()));
    assertFalse(result.getSignals().contains(Signal.WEAPON_DETECTED));
  }

  @ParameterizedTest
  @MethodSource("contextObjects")
  void execute_positiveCountsDoNotBypassTheConfidenceThreshold(String label, Signal ignoredSignal) {
    for (double confidence : List.of(0.29, 0.3)) {
      StepResult result =
          execute(
              new ObjectDetectionResult(
                  List.of(new DetectedObject(label, confidence)), Map.of(label, 1)));

      assertEquals(List.of(Signal.NO_THREAT_OBJECT), result.getSignals());
    }
    StepResult countsOnly = execute(new ObjectDetectionResult(List.of(), Map.of(label, 1)));
    assertEquals(List.of(Signal.NO_THREAT_OBJECT), countsOnly.getSignals());
  }

  @ParameterizedTest
  @MethodSource("contextObjects")
  void execute_partialDetectionRetainsContextWithoutClaimingThereAreNoThreats(
      String label, Signal expectedSignal) {
    StepResult result =
        execute(
            new ObjectDetectionResult(
                List.of(new DetectedObject(label, 0.95)), Map.of(), AnalysisQuality.INSUFFICIENT));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertEquals(List.of(expectedSignal), result.getSignals());
  }

  @Test
  void execute_weaponAndContextObjectsRetainTheWeaponFinding() {
    StepResult result =
        execute(
            new ObjectDetectionResult(
                List.of(
                    new DetectedObject("Firearm", 0.95),
                    new DetectedObject("Tool", 0.95),
                    new DetectedObject("ladder", 0.95),
                    new DetectedObject("package", 0.95)),
                Map.of("Firearm", 1, "Tool", 1, "ladder", 1, "package", 1)));

    assertEquals(
        Set.of(
            Signal.WEAPON_DETECTED,
            Signal.TOOL_DETECTED,
            Signal.LADDER_DETECTED,
            Signal.PACKAGE_DETECTED),
        Set.copyOf(result.getSignals()));
  }

  private static Stream<Arguments> contextObjects() {
    return Stream.of(
        Arguments.of("ladder", Signal.LADDER_DETECTED),
        Arguments.of("Tool", Signal.TOOL_DETECTED),
        Arguments.of("package", Signal.PACKAGE_DETECTED));
  }

  private StepResult execute(ObjectDetectionResult detection) {
    return new ObjectRecognitionStep(ignored -> detection)
        .execute(StepContext.builder().mediaType(ChunkType.IMAGE).payload(new byte[] {1}).build());
  }
}
