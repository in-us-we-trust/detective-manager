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
                Signal.REPEATED_DIRECTION_CHANGES,
                Signal.LOITERING));

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(
        "Persona desconocida con posible comportamiento de merodeo"
            + " · Persona desconocida con movimientos erráticos"
            + " · Persona presente durante un período prolongado",
        assessment.getMessage());
  }

  @Test
  void prolongedPresenceWithOneReturn_isOnlyAttentionWorthy() {
    Assessment assessment =
        evaluator.evaluate(
            List.of(Signal.PROLONGED_PRESENCE, Signal.RETURNED_TO_PREVIOUS_POSITION));

    assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(
        "Persona presente durante un período prolongado"
            + " · Se detectó un regreso a una posición anterior",
        assessment.getMessage());
  }

  @Test
  void fastApproachWithUnknownPerson_keepsOnlyTheUnknownPersonAssessment() {
    Assessment assessment =
        evaluator.evaluate(
            List.of(Signal.UNKNOWN_PERSON, Signal.APPROACHING_CAMERA, Signal.FAST_APPROACH));

    assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(evaluator.evaluate(List.of(Signal.UNKNOWN_PERSON)), assessment);
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
    assertEquals(
        "La persona está armada"
            + " · Amenaza verbal detectada"
            + " · Persona desconocida con posible comportamiento de merodeo",
        weapon.getMessage());
    assertEquals(Verdict.DANGER, threat.getVerdict());
    assertEquals(
        "Amenaza verbal detectada · Persona desconocida con posible comportamiento de merodeo",
        threat.getMessage());
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
        Severity.LLAMATIVO,
        "Persona presente durante un período prolongado · Persona detenida frente a la puerta");
    assertAssessment(
        List.of(Signal.LOITERING), Severity.SOSPECHOSO, "Posible comportamiento de merodeo");
    assertAssessment(
        List.of(Signal.APPROACHING_CAMERA, Signal.UNKNOWN_PERSON),
        Severity.LLAMATIVO,
        "No se reconoce a la persona");
    assertAssessment(
        List.of(Signal.PROLONGED_PRESENCE),
        Severity.LLAMATIVO,
        "Persona presente durante un período prolongado");
  }

  @Test
  void whiteSignals_areReportedAsNormal() {
    assertAssessment(
        List.of(Signal.NO_SUSPICIOUS_CONVERSATION),
        Severity.NORMAL,
        "No se detectó nada extraño en la conversación");
    assertAssessment(
        List.of(Signal.NO_SUSPICIOUS_BEHAVIOR),
        Severity.NORMAL,
        "No se detectó nada extraño en el video");
    assertAssessment(
        List.of(
            Signal.UNKNOWN_PERSON,
            Signal.NO_SUSPICIOUS_CONVERSATION,
            Signal.NO_SUSPICIOUS_BEHAVIOR),
        Severity.LLAMATIVO,
        "No se reconoce a la persona"
            + " · No se detectó nada extraño en la conversación"
            + " · No se detectó nada extraño en el video");
  }

  @Test
  void whiteSignals_areDroppedWhenTheTrackHasFindingsFromTheSameSource() {
    assertAssessment(
        List.of(Signal.NO_SUSPICIOUS_CONVERSATION, Signal.URGENCY),
        Severity.LLAMATIVO,
        "Habla con urgencia");
    assertAssessment(
        List.of(Signal.NO_SUSPICIOUS_BEHAVIOR, Signal.LOITERING),
        Severity.SOSPECHOSO,
        "Posible comportamiento de merodeo");
  }

  private void assertAssessment(
      List<Signal> signals, Severity expectedSeverity, String expectedMessage) {
    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(expectedSeverity, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(expectedMessage, assessment.getMessage());
  }
}
