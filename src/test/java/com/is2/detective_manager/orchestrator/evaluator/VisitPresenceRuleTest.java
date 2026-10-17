package com.is2.detective_manager.orchestrator.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class VisitPresenceRuleTest {

  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();

  @Test
  void observedVisitTimeRequiresAttentionEvenAfterRecognizingSomeone() {
    for (List<Signal> identity :
        List.of(
            List.<Signal>of(),
            List.of(Signal.UNKNOWN_PERSON),
            List.of(Signal.KNOWN_PERSON, Signal.NO_THREAT_OBJECT))) {
      List<Signal> signals = new ArrayList<>(identity);
      signals.add(Signal.PROLONGED_VISIT_PRESENCE);
      signals.add(Signal.NO_SUSPICIOUS_BEHAVIOR);
      Assessment result = evaluator.evaluate(signals);
      assertEquals(Verdict.CONTINUE, result.getVerdict());
      assertEquals(Severity.LLAMATIVO, result.getSeverity());
      assertTrue(result.getMessage().startsWith(RuleBasedDangerEvaluator.VISIT_PRESENCE_MESSAGE));
      assertFalse(result.getMessage().contains("merodeo"));
      assertFalse(result.getMessage().contains("nada extraño en el video"));
    }
    assertEquals(Severity.LLAMATIVO, SignalSeverity.of(Signal.PROLONGED_VISIT_PRESENCE));
  }

  @Test
  void presenceAcrossClipsDoesNotAssertOnePersonOrActivatePerPersonCombinations() {
    Assessment result =
        evaluator.evaluate(List.of(Signal.PROLONGED_VISIT_PRESENCE, Signal.LONG_STOP));
    assertEquals(Severity.LLAMATIVO, result.getSeverity());
    assertEquals(Verdict.CONTINUE, result.getVerdict());
    assertFalse(result.getMessage().contains("Permanencia prolongada con detención extensa"));
  }

  @Test
  void knownPersonUrgencyAndPressureKeepPriority() {
    Assessment result =
        evaluator.evaluate(
            List.of(
                Signal.KNOWN_PERSON,
                Signal.NO_THREAT_OBJECT,
                Signal.URGENCY,
                Signal.PRESSURE,
                Signal.PROLONGED_VISIT_PRESENCE));
    assertEquals(Severity.LLAMATIVO, result.getSeverity());
    assertEquals(Verdict.CONTINUE, result.getVerdict());
    assertTrue(result.getMessage().startsWith(KnownPersonAttention.URGENCY_AND_PRESSURE_MESSAGE));
  }

  @Test
  void suspiciousRulesKeepPriority() {
    Assessment result =
        evaluator.evaluate(
            List.of(Signal.AUTHORITY, Signal.MONEY_REQUEST, Signal.PROLONGED_VISIT_PRESENCE));
    assertEquals(Severity.SOSPECHOSO, result.getSeverity());
    assertEquals(Verdict.CONTINUE, result.getVerdict());
    assertTrue(
        result
            .getMessage()
            .startsWith("Verificá la identidad y el motivo del pedido antes de pagar."));
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"WEAPON_DETECTED", "THREAT"})
  void dangerousRulesKeepPriority(Signal critical) {
    Assessment result = evaluator.evaluate(List.of(critical, Signal.PROLONGED_VISIT_PRESENCE));
    assertEquals(Severity.PELIGROSO, result.getSeverity());
    assertEquals(Verdict.DANGER, result.getVerdict());
  }
}
