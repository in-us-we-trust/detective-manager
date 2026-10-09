package com.is2.detective_manager.orchestrator.step.conversationanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.StepContext;
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

  private List<Signal> execute(List<Evidence> evidences) {
    ConversationAnalysisStep step = new ConversationAnalysisStep(ignored -> evidences);
    return step.execute(
            StepContext.builder()
                .trackId(UUID.randomUUID().toString())
                .deviceId(UUID.randomUUID())
                .mediaType(ChunkType.AUDIO)
                .payload(new byte[] {1})
                .accumulated(Map.of())
                .build())
        .getSignals();
  }
}
