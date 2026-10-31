package com.is2.detective_manager.orchestrator.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class RuleBasedDangerEvaluatorArrivalTest {

  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();

  @ParameterizedTest
  @MethodSource("ordinaryArrivals")
  void ordinaryArrivalsRemainAttentionWorthyWithoutChangingDetectedSignals(List<Signal> findings) {
    List<Signal> signals = new ArrayList<>(findings);
    List<Signal> original = List.copyOf(signals);

    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(AssessmentStatus.EVALUATING, assessment.getStatus());
    assertEquals(original, signals);
    Collections.reverse(signals);
    assertEquals(assessment, evaluator.evaluate(signals));
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"FAST_APPROACH", "MULTIPLE_PEOPLE"})
  void recognizedArrivalsKeepTheExistingSafePriorityWhenThereAreNoOtherFindings(Signal finding) {
    Assessment assessment =
        evaluator.evaluate(List.of(Signal.KNOWN_PERSON, Signal.NO_THREAT_OBJECT, finding));

    assertEquals(Severity.NORMAL, assessment.getSeverity());
    assertEquals(Verdict.SAFE, assessment.getVerdict());
    assertEquals(AssessmentStatus.CONCLUDED, assessment.getStatus());
    assertTrue(assessment.getMessage().startsWith("Persona reconocida"));
  }

  @ParameterizedTest
  @MethodSource("suspiciousArrivals")
  void additionalSuspiciousEvidenceKeepsPriority(List<Signal> signals, String expectedTitle) {
    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(AssessmentStatus.EVALUATING, assessment.getStatus());
    assertTrue(assessment.getMessage().startsWith(expectedTitle));
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"WEAPON_DETECTED", "THREAT"})
  void criticalFindingsKeepPriorityOverQuickApproachesAndGroups(Signal critical) {
    Assessment assessment =
        evaluator.evaluate(
            List.of(
                Signal.UNKNOWN_PERSON,
                Signal.MULTIPLE_PEOPLE,
                Signal.FAST_APPROACH,
                Signal.NO_THREAT_OBJECT,
                critical));

    assertEquals(Severity.PELIGROSO, assessment.getSeverity());
    assertEquals(Verdict.DANGER, assessment.getVerdict());
    assertEquals(AssessmentStatus.CONCLUDED, assessment.getStatus());
    assertTrue(
        assessment
            .getMessage()
            .startsWith(
                critical == Signal.WEAPON_DETECTED
                    ? "La persona está armada"
                    : "Amenaza verbal detectada"));
  }

  private static Stream<List<Signal>> ordinaryArrivals() {
    return Stream.of(
        List.of(Signal.UNKNOWN_PERSON, Signal.FAST_APPROACH),
        List.of(
            Signal.UNKNOWN_PERSON,
            Signal.NO_THREAT_OBJECT,
            Signal.APPROACHING_CAMERA,
            Signal.FAST_APPROACH,
            Signal.PACKAGE_DETECTED,
            Signal.NO_SUSPICIOUS_CONVERSATION),
        List.of(Signal.MULTIPLE_PEOPLE),
        List.of(Signal.UNKNOWN_PERSON, Signal.MULTIPLE_PEOPLE),
        List.of(
            Signal.UNKNOWN_PERSON,
            Signal.MULTIPLE_PEOPLE,
            Signal.NO_THREAT_OBJECT,
            Signal.TOOL_DETECTED,
            Signal.NO_SUSPICIOUS_CONVERSATION),
        List.of(Signal.UNKNOWN_PERSON, Signal.MULTIPLE_PEOPLE, Signal.FAST_APPROACH),
        List.of(Signal.KNOWN_PERSON, Signal.MULTIPLE_PEOPLE));
  }

  private static Stream<Arguments> suspiciousArrivals() {
    return Stream.of(
        Arguments.of(
            List.of(Signal.UNKNOWN_PERSON, Signal.FAST_APPROACH, Signal.URGENCY, Signal.PRESSURE),
            "Presiona para que se actúe con urgencia"),
        Arguments.of(
            List.of(Signal.UNKNOWN_PERSON, Signal.MULTIPLE_PEOPLE, Signal.PRESSURE),
            "Varias personas en la entrada y presión para que actúes."));
  }
}
