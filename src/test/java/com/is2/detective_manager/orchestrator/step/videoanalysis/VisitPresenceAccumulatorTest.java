package com.is2.detective_manager.orchestrator.step.videoanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class VisitPresenceAccumulatorTest {

  private static final LocalDateTime START = LocalDateTime.of(2026, 10, 16, 12, 0);

  @Test
  void consecutiveNumberedClipsReachTwentyDespiteOutOfOrderDeliveryAndRetries() {
    assertTime(20, clip(1, 5.0), clip(4, 5.0), clip(2, 5.0), clip(2, 5.0), clip(3, 5.0));
  }

  @Test
  void missingNumberBreaksTheSequenceUntilItIsActuallyAnalyzed() {
    assertTime(10, clip(1, 5.0), clip(2, 5.0), clip(4, 5.0), clip(5, 5.0));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
  void absenceAndUnreliableAnalysesResetCurrentTime(Double seconds) {
    assertTime(10, clip(1, 5.0), clip(2, 5.0), clip(3, seconds), clip(4, 5.0), clip(5, 5.0));
  }

  @Test
  void retryCanReplaceAFailedAnalysisWithoutCountingTheClipTwice() {
    assertTime(20, clip(1, 5.0), clip(2, null), clip(3, 5.0), clip(2, 5.0), clip(4, 5.0));
    assertTime(20, clip(1, 5.0), clip(2, 5.0), clip(2, null), clip(3, 5.0), clip(4, 5.0));
  }

  @Test
  void firstValidResultForTheSameClipWinsOverAConflictingRetry() {
    assertTime(10, clip(1, 5.0), clip(2, 0.0), clip(2, 5.0), clip(3, 5.0), clip(4, 5.0));
  }

  @Test
  void confirmedPresenceRemainsInHistoryAfterAbsenceOrFailure() {
    assertTime(
        20, clip(1, 5.0), clip(2, 5.0), clip(3, 5.0), clip(4, 5.0), clip(5, 0.0), clip(6, null));
  }

  @Test
  void legacyRecordsWithNoTimingEvidenceAreBarriers() {
    assertTime(
        10,
        clip(1, 5.0),
        clip(2, 5.0),
        new VideoPresenceChunk(null, null, null, START.plusSeconds(3)),
        clip(3, 5.0),
        clip(4, 5.0));
  }

  @Test
  void unnumberedPositiveClipsDeduplicateByContent() {
    assertTime(
        15, hashed("a", 5.0, 1), hashed("b", 5.0, 2), hashed("a", 5.0, 3), hashed("c", 5.0, 4));
  }

  @Test
  void identicalUnnumberedEmptyClipsRemainSeparateAbsenceBarriers() {
    assertTime(
        5,
        hashed("a", 5.0, 1),
        hashed("empty", 0.0, 2),
        hashed("b", 5.0, 3),
        hashed("empty", 0.0, 4),
        hashed("c", 5.0, 5));
  }

  @Test
  void changingNumberingSchemeDoesNotBridgeUncertainSequences() {
    assertTime(
        10,
        clip(1, 5.0),
        clip(2, 5.0),
        hashed("a", 5.0, 3),
        new VideoPresenceChunk("number:3", 3L, 5.0, START.plusSeconds(4)));
  }

  private VideoPresenceChunk clip(long number, Double seconds) {
    return new VideoPresenceChunk("number:" + number, number, seconds, START.plusSeconds(number));
  }

  private VideoPresenceChunk hashed(String key, Double seconds, int order) {
    return new VideoPresenceChunk(key, null, seconds, START.plusSeconds(order));
  }

  private void assertTime(double expected, VideoPresenceChunk... clips) {
    assertEquals(expected, VisitPresenceAccumulator.maximumObservedSeconds(List.of(clips)), 1e-6);
  }
}
