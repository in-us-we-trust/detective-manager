package com.is2.detective_manager.orchestrator.step.conversationanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationAnalysisStepTest {

  @Test
  void execute_emitsNoSuspiciousConversationWhenNoEvidenceExceedsThreshold() {
    assertEquals(List.of(Signal.NO_SUSPICIOUS_CONVERSATION), execute(List.of()));
    assertEquals(
        List.of(Signal.NO_SUSPICIOUS_CONVERSATION),
        execute(List.of(new Evidence("URGENCY", 0.5), new Evidence("THREAT", 0.1))));
  }

  @Test
  void execute_doesNotEmitNoSuspiciousConversationWhenSomethingWasDetected() {
    assertEquals(
        List.of(Signal.URGENCY),
        execute(List.of(new Evidence("URGENCY", 0.9), new Evidence("THREAT", 0.1))));
  }

  @Test
  void execute_emptyTranscriptHasInsufficientInformationAndNoReassuringSignal() {
    StepResult result = execute(new ConversationAnalysisResult(" \n\t", List.of()));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertTrue(result.getSignals().isEmpty());
    assertNotNull(result.getMessage());
  }

  @Test
  void execute_usableTranscriptWithoutEvidenceIsAValidFinding() {
    StepResult result = execute(new ConversationAnalysisResult("Buenas tardes.", List.of()));

    assertEquals(AnalysisQuality.VALID, result.getQuality());
    assertEquals(List.of(Signal.NO_SUSPICIOUS_CONVERSATION), result.getSignals());
  }

  @Test
  void execute_partialEvidenceRetainsThreatWithoutReassuringSignal() {
    StepResult result =
        execute(
            new ConversationAnalysisResult(
                "Amenaza explícita.",
                List.of(new Evidence("THREAT", 0.9)),
                AnalysisQuality.INSUFFICIENT));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertEquals(List.of(Signal.THREAT), result.getSignals());
    assertFalse(result.getSignals().contains(Signal.NO_SUSPICIOUS_CONVERSATION));
  }

  @Test
  void execute_unrecognizedEvidenceDoesNotClaimConversationIsSafe() {
    StepResult result =
        execute(
            new ConversationAnalysisResult(
                "Texto analizado.", List.of(new Evidence("UNRECOGNIZED_TYPE", 0.9))));

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertTrue(result.getSignals().isEmpty());
  }

  private List<Signal> execute(List<Evidence> evidences) {
    return execute(new ConversationAnalysisResult("Texto analizado.", evidences)).getSignals();
  }

  private StepResult execute(ConversationAnalysisResult analysis) {
    ConversationAnalysisStep step = new ConversationAnalysisStep(ignored -> analysis);
    return step.execute(
        StepContext.builder()
            .trackId(UUID.randomUUID().toString())
            .deviceId(UUID.randomUUID())
            .mediaType(ChunkType.AUDIO)
            .payload(new byte[] {1})
            .accumulated(Map.of())
            .build());
  }
}
