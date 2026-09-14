package com.is2.detective_manager.orchestrator.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedDangerEvaluatorVideoTest {

  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();

  @Test
  void loiteringWithUnknownPerson_hasPriorityOverGenericRules() {
    Assessment assessment =
        evaluator.evaluate(
            List.of(
                Signal.UNKNOWN_PERSON,
                Signal.PROLONGED_PRESENCE,
                Signal.LONG_STOP,
                Signal.LOITERING));

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(
        "Persona desconocida con posible comportamiento de merodeo", assessment.getMessage());
  }

  @Test
  void specificLoiteringCause_hasPriorityOverGenericLoitering() {
    Assessment assessment =
        evaluator.evaluate(
            List.of(
                Signal.PROLONGED_PRESENCE, Signal.RETURNED_TO_PREVIOUS_POSITION, Signal.LOITERING));

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals("Permanencia prolongada con movimientos de ida y vuelta", assessment.getMessage());
  }

  @Test
  void fastApproachWithUnknownPerson_isSuspicious() {
    Assessment assessment =
        evaluator.evaluate(
            List.of(Signal.UNKNOWN_PERSON, Signal.APPROACHING_CAMERA, Signal.FAST_APPROACH));

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals("Persona desconocida acercándose rápidamente", assessment.getMessage());
  }

  @Test
  void criticalExistingRules_keepMaximumPriority() {
    Assessment weapon =
        evaluator.evaluate(
            List.of(
                Signal.WEAPON_DETECTED, Signal.THREAT, Signal.UNKNOWN_PERSON, Signal.LOITERING));
    Assessment threat =
        evaluator.evaluate(List.of(Signal.THREAT, Signal.UNKNOWN_PERSON, Signal.LOITERING));

    assertEquals(Verdict.DANGER, weapon.getVerdict());
    assertEquals("La persona está armada", weapon.getMessage());
    assertEquals(Verdict.DANGER, threat.getVerdict());
    assertEquals("Amenaza verbal detectada", threat.getMessage());
  }

  @Test
  void videoSignals_neverProduceDangerByThemselves() {
    List<List<Signal>> cases =
        List.of(
            List.of(Signal.PROLONGED_PRESENCE),
            List.of(Signal.LONG_STOP),
            List.of(Signal.REPEATED_DIRECTION_CHANGES),
            List.of(Signal.RETURNED_TO_PREVIOUS_POSITION),
            List.of(Signal.APPROACHING_CAMERA),
            List.of(Signal.FAST_APPROACH),
            List.of(Signal.LOITERING),
            List.of(Signal.LOITERING, Signal.UNKNOWN_PERSON));

    cases.forEach(
        signals -> assertNotEquals(Verdict.DANGER, evaluator.evaluate(signals).getVerdict()));
  }

  @Test
  void remainingVideoRules_returnExpectedNonCriticalAssessments() {
    assertAssessment(
        List.of(Signal.PROLONGED_PRESENCE, Signal.LONG_STOP),
        Severity.SOSPECHOSO,
        "Permanencia prolongada con detención extensa");
    assertAssessment(
        List.of(Signal.LOITERING), Severity.SOSPECHOSO, "Posible comportamiento de merodeo");
    assertAssessment(
        List.of(Signal.APPROACHING_CAMERA, Signal.UNKNOWN_PERSON),
        Severity.LLAMATIVO,
        "Persona desconocida acercándose");
    assertAssessment(
        List.of(Signal.PROLONGED_PRESENCE),
        Severity.LLAMATIVO,
        "Persona presente durante un período prolongado");
  }

  private void assertAssessment(
      List<Signal> signals, Severity expectedSeverity, String expectedMessage) {
    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(expectedSeverity, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(expectedMessage, assessment.getMessage());
  }
}
