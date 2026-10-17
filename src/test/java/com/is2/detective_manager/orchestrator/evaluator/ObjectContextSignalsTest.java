package com.is2.detective_manager.orchestrator.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.mapper.TrackAppCode;
import com.is2.detective_manager.mapper.TrackDisplay;
import com.is2.detective_manager.mapper.TrackMessageBuilder;
import com.is2.detective_manager.orchestrator.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ObjectContextSignalsTest {

  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();

  @ParameterizedTest
  @MethodSource("contextSignals")
  void contextAloneIsNormalAndDoesNotConcludeSafety(Signal context, String expectedMessage) {
    Assessment assessment = evaluator.evaluate(List.of(context));

    assertEquals(Severity.NORMAL, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(AssessmentStatus.EVALUATING, assessment.getStatus());
    assertEquals(expectedMessage, assessment.getMessage());
    assertEquals(Severity.NORMAL, SignalSeverity.maxOf(List.of(context)));
  }

  @ParameterizedTest
  @MethodSource("contextSignals")
  void contextAccompaniesRecognizedAndUnknownVisitorsWithoutChangingTheirAssessment(
      Signal context, String expectedMessage) {
    for (Signal identity : List.of(Signal.KNOWN_PERSON, Signal.UNKNOWN_PERSON)) {
      List<Signal> signals = new ArrayList<>(List.of(identity, Signal.NO_THREAT_OBJECT));
      Assessment baseline = evaluator.evaluate(signals);
      signals.add(context);

      Assessment assessment = evaluator.evaluate(signals);

      assertSameDecision(baseline, assessment);
      assertEquals(baseline.getMessage() + " · " + expectedMessage, assessment.getMessage());
    }
  }

  @ParameterizedTest
  @MethodSource("contextSignals")
  void contextDoesNotReclassifyTheKnownVisitorWhoMightNeedHelp(
      Signal context, String expectedMessage) {
    for (List<Signal> request :
        List.of(
            List.of(Signal.URGENCY),
            List.of(Signal.PRESSURE),
            List.of(Signal.URGENCY, Signal.PRESSURE))) {
      List<Signal> signals = new ArrayList<>(List.of(Signal.KNOWN_PERSON, Signal.NO_THREAT_OBJECT));
      signals.addAll(request);
      Assessment baseline = evaluator.evaluate(signals);
      signals.add(context);

      Assessment assessment = evaluator.evaluate(signals);

      assertSameDecision(baseline, assessment);
      assertEquals(baseline.getMessage() + " · " + expectedMessage, assessment.getMessage());
    }
  }

  @ParameterizedTest
  @MethodSource("contextSignals")
  void contextRemainsBehindSuspiciousAndCriticalFindings(Signal context, String expectedMessage) {
    for (List<Signal> findings :
        List.of(
            List.of(Signal.LOITERING),
            List.of(Signal.VERIFICATION_AVOIDANCE),
            List.of(Signal.COVERED_FACE),
            List.of(Signal.AUTHORITY, Signal.MONEY_REQUEST),
            List.of(Signal.COVERED_FACE, Signal.FAST_APPROACH),
            List.of(Signal.WEAPON_DETECTED),
            List.of(Signal.THREAT),
            List.of(Signal.WEAPON_DETECTED, Signal.THREAT))) {
      List<Signal> signals = new ArrayList<>(findings);
      Assessment baseline = evaluator.evaluate(signals);
      signals.add(context);

      Assessment assessment = evaluator.evaluate(signals);

      assertSameDecision(baseline, assessment);
      assertEquals(baseline.getMessage() + " · " + expectedMessage, assessment.getMessage());
    }
  }

  @ParameterizedTest
  @MethodSource("contextSignals")
  void historyDisplaysContextWithExistingPresentationCodes(Signal context, String expectedMessage) {
    for (TrackAppCode code :
        List.of(TrackAppCode.ACTIVITY, TrackAppCode.KNOWN_PERSON, TrackAppCode.UNKNOWN_PERSON)) {
      List<Signal> signals = new ArrayList<>(List.of(context));
      if (code == TrackAppCode.KNOWN_PERSON) {
        signals.add(Signal.KNOWN_PERSON);
        signals.add(Signal.NO_THREAT_OBJECT);
      } else if (code == TrackAppCode.UNKNOWN_PERSON) {
        signals.add(Signal.UNKNOWN_PERSON);
      }
      String csv = String.join(",", signals.stream().map(Enum::name).toList());

      TrackDisplay display = new TrackMessageBuilder(evaluator).build(csv);

      assertEquals(code, display.code());
      assertTrue(display.title().contains(expectedMessage));
      assertEquals(evaluator.evaluate(signals).getMessage(), display.title());
    }
  }

  @Test
  void allThreeObjectsAreReportedWithoutAssigningThemToAPersonOrAssumingTheirUse() {
    Assessment assessment =
        evaluator.evaluate(
            List.of(
                Signal.PERSON,
                Signal.NO_THREAT_OBJECT,
                Signal.PACKAGE_DETECTED,
                Signal.TOOL_DETECTED,
                Signal.LADDER_DETECTED));

    assertEquals(Severity.NORMAL, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(
        "Persona detectada, sin objetos peligrosos"
            + " · Se detectó una escalera durante la visita"
            + " · Se detectó una herramienta durante la visita"
            + " · Se detectó un paquete durante la visita",
        assessment.getMessage());
  }

  @Test
  void contextDoesNotDisplaceHigherPriorityDetailsWhenTheMessageIsFull() {
    List<Signal> findings =
        List.of(
            Signal.COVERED_FACE,
            Signal.FAST_APPROACH,
            Signal.LOITERING,
            Signal.VERIFICATION_AVOIDANCE,
            Signal.MULTIPLE_PEOPLE);
    Assessment baseline = evaluator.evaluate(findings);
    List<Signal> withContext = new ArrayList<>(findings);
    withContext.addAll(
        List.of(Signal.PACKAGE_DETECTED, Signal.TOOL_DETECTED, Signal.LADDER_DETECTED));

    assertEquals(baseline, evaluator.evaluate(withContext));
  }

  private void assertSameDecision(Assessment expected, Assessment actual) {
    assertEquals(expected.getSeverity(), actual.getSeverity());
    assertEquals(expected.getVerdict(), actual.getVerdict());
    assertEquals(expected.getStatus(), actual.getStatus());
  }

  private static Stream<Arguments> contextSignals() {
    return Stream.of(
        Arguments.of(Signal.LADDER_DETECTED, "Se detectó una escalera durante la visita"),
        Arguments.of(Signal.TOOL_DETECTED, "Se detectó una herramienta durante la visita"),
        Arguments.of(Signal.PACKAGE_DETECTED, "Se detectó un paquete durante la visita"));
  }
}
