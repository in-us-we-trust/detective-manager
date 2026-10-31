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

class InformationalVideoSignalsTest {

  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"APPROACHING_CAMERA", "FAST_APPROACH"})
  void approachesAloneAreNormalAndHaveNeutralDescriptions(Signal approach) {
    Assessment assessment = evaluator.evaluate(List.of(approach));

    assertEquals(Severity.NORMAL, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(
        approach == Signal.FAST_APPROACH
            ? "Se detectó un acercamiento rápido a la cámara"
            : "Persona acercándose a la puerta",
        assessment.getMessage());
    assertFalse(assessment.getMessage().contains("Revisá"));
    Assessment complete = evaluator.evaluate(List.of(approach, Signal.NO_SUSPICIOUS_BEHAVIOR));
    assertEquals(Severity.NORMAL, complete.getSeverity());
    assertTrue(complete.getMessage().contains("No se detectó nada extraño en el video"));
  }

  @Test
  void knownAndUnknownVisitorsKeepTheirOwnAssessmentWithEitherApproach() {
    for (List<Signal> identity :
        List.of(
            List.of(Signal.KNOWN_PERSON),
            List.of(Signal.KNOWN_PERSON, Signal.NO_THREAT_OBJECT),
            List.of(Signal.UNKNOWN_PERSON),
            List.of(Signal.UNKNOWN_PERSON, Signal.NO_THREAT_OBJECT))) {
      Assessment baseline = evaluator.evaluate(identity);
      for (Signal approach : List.of(Signal.APPROACHING_CAMERA, Signal.FAST_APPROACH)) {
        List<Signal> signals = new ArrayList<>(identity);
        signals.add(approach);
        assertEquals(baseline, evaluator.evaluate(signals));
      }
    }
  }

  @Test
  void informationalApproachesDoNotDiscardSuccessfullyAnalyzedBehavior() {
    for (Signal identity : List.of(Signal.KNOWN_PERSON, Signal.UNKNOWN_PERSON)) {
      List<Signal> signals =
          new ArrayList<>(
              List.of(
                  identity,
                  Signal.APPROACHING_CAMERA,
                  Signal.FAST_APPROACH,
                  Signal.NO_SUSPICIOUS_BEHAVIOR));
      List<Signal> original = List.copyOf(signals);

      Assessment assessment = evaluator.evaluate(signals);

      assertEquals(
          identity == Signal.KNOWN_PERSON ? Severity.NORMAL : Severity.LLAMATIVO,
          assessment.getSeverity());
      assertTrue(assessment.getMessage().contains("No se detectó nada extraño en el video"));
      assertEquals(original, signals);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"APPROACHING_CAMERA", "FAST_APPROACH"},
      mode = EnumSource.Mode.EXCLUDE)
  void independentSignalsKeepTheirSeverityVerdictAndStatus(Signal independent) {
    for (Signal identity : List.of(Signal.KNOWN_PERSON, Signal.UNKNOWN_PERSON)) {
      List<Signal> signals =
          new ArrayList<>(List.of(identity, Signal.NO_THREAT_OBJECT, independent));
      Assessment baseline = evaluator.evaluate(signals);
      signals.add(Signal.APPROACHING_CAMERA);
      signals.add(Signal.FAST_APPROACH);

      Assessment actual = evaluator.evaluate(signals);

      assertEquals(baseline, actual);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {
        "LOITERING",
        "LONG_STOP",
        "PROLONGED_PRESENCE",
        "PROLONGED_VISIT_PRESENCE",
        "REPEATED_DIRECTION_CHANGES",
        "RETURNED_TO_PREVIOUS_POSITION"
      })
  void actualBehaviorFindingsStillInvalidateReassurance(Signal suspicious) {
    List<Signal> signals =
        new ArrayList<>(
            List.of(
                suspicious,
                Signal.APPROACHING_CAMERA,
                Signal.FAST_APPROACH,
                Signal.NO_SUSPICIOUS_BEHAVIOR));

    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(evaluator.evaluate(List.of(suspicious)), assessment);
    assertFalse(assessment.getMessage().contains("No se detectó nada extraño"));
    assertTrue(signals.contains(Signal.NO_SUSPICIOUS_BEHAVIOR));
  }
}
