package com.is2.detective_manager.orchestrator.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SignalSeverityTest {

  @Test
  void multiplePeopleOnlyRankAsAttentionWithoutOtherFindings() {
    assertEquals(Severity.LLAMATIVO, SignalSeverity.maxOf(List.of(Signal.MULTIPLE_PEOPLE)));
  }

  @Test
  void coveredFaceRanksAboveAnUnknownGroup() {
    assertEquals(
        Severity.SOSPECHOSO,
        SignalSeverity.maxOf(
            List.of(Signal.MULTIPLE_PEOPLE, Signal.UNKNOWN_PERSON, Signal.COVERED_FACE)));
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"WEAPON_DETECTED", "THREAT"})
  void dangerKeepsPriorityOverAnUnknownGroup(Signal dangerFinding) {
    assertEquals(
        Severity.PELIGROSO,
        SignalSeverity.maxOf(
            List.of(Signal.MULTIPLE_PEOPLE, Signal.UNKNOWN_PERSON, dangerFinding)));
  }
}
