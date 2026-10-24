package com.is2.detective_manager.orchestrator.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class RuleBasedDangerEvaluatorKnownPersonTest {

  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();

  @ParameterizedTest
  @MethodSource("requestsNeedingAttention")
  void recognizedPersonWithUrgencyOrPressure_keepsEvaluatingInsteadOfSafe(
      List<Signal> requestSignals, String expectedMessage) {
    Assessment assessment = evaluator.evaluate(recognizedVisitor(requestSignals));

    assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(AssessmentStatus.EVALUATING, assessment.getStatus());
    assertEquals(expectedMessage, assessment.getMessage());
  }

  @Test
  void recognizedPersonWithoutRequestSignals_canStillBeSafeWithOnlyImageEvidence() {
    Assessment assessment = evaluator.evaluate(recognizedVisitor(List.of()));

    assertEquals(Severity.NORMAL, assessment.getSeverity());
    assertEquals(Verdict.SAFE, assessment.getVerdict());
    assertEquals(AssessmentStatus.CONCLUDED, assessment.getStatus());
    assertEquals("Persona reconocida", assessment.getMessage());
  }

  @Test
  void recognizedPersonWithUrgency_doesNotNeedAReassuringObjectSignalToRequireAttention() {
    Assessment assessment = evaluator.evaluate(List.of(Signal.KNOWN_PERSON, Signal.URGENCY));

    assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(
        "Alguien conocido podría necesitar ayuda. Revisá la situación.", assessment.getMessage());
  }

  @ParameterizedTest
  @MethodSource("requestsNeedingAttention")
  void mixedRecognitionDoesNotReceiveTheRecognizedVisitorException(
      List<Signal> requestSignals, String ignoredMessage) {
    List<Signal> signals = recognizedVisitor(requestSignals);
    signals.add(Signal.UNKNOWN_PERSON);

    Assessment assessment = evaluator.evaluate(signals);

    if (requestSignals.size() == 2) {
      assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
      assertEquals(Verdict.CONTINUE, assessment.getVerdict());
      assertTrue(assessment.getMessage().startsWith("Presiona para que se actúe con urgencia"));
    } else {
      assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
      assertEquals(Verdict.CONTINUE, assessment.getVerdict());
      assertTrue(assessment.getMessage().startsWith(MixedRecognitionEvidence.MESSAGE));
    }
    assertFalse(assessment.getMessage().contains("podría necesitar ayuda"));
    assertFalse(assessment.getMessage().contains("conviene revisar la situación"));
  }

  @Test
  void urgencyAndPressureWithoutARecognizedPerson_remainSuspicious() {
    Assessment assessment = evaluator.evaluate(List.of(Signal.URGENCY, Signal.PRESSURE));

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals("Presiona para que se actúe con urgencia", assessment.getMessage());
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"WEAPON_DETECTED", "THREAT"})
  void criticalFindings_keepPriorityOverRecognizedVisitorAttention(Signal criticalFinding) {
    List<Signal> signals = recognizedVisitor(List.of(Signal.URGENCY, Signal.PRESSURE));
    signals.add(criticalFinding);

    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(Severity.PELIGROSO, assessment.getSeverity());
    assertEquals(Verdict.DANGER, assessment.getVerdict());
    assertEquals(AssessmentStatus.CONCLUDED, assessment.getStatus());
    assertTrue(
        assessment
            .getMessage()
            .startsWith(
                criticalFinding == Signal.WEAPON_DETECTED
                    ? "La persona está armada"
                    : "Amenaza verbal detectada"));
    assertFalse(assessment.getMessage().contains("podría necesitar ayuda"));
  }

  @ParameterizedTest
  @MethodSource("otherSuspiciousPatterns")
  void otherSuspiciousFindings_keepPriorityAndDoNotSuggestHelp(List<Signal> suspiciousPattern) {
    List<Signal> signals = recognizedVisitor(List.of(Signal.URGENCY, Signal.PRESSURE));
    signals.addAll(suspiciousPattern);

    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(AssessmentStatus.EVALUATING, assessment.getStatus());
    assertFalse(assessment.getMessage().contains("podría necesitar ayuda"));
    assertFalse(assessment.getMessage().contains("conviene revisar la situación"));
    assertFalse(assessment.getMessage().startsWith("Persona reconocida"));
  }

  @Test
  void prolongedWaitDoesNotEscalateRecognizedVisitorUrgencyAndPressureToSuspicious() {
    Assessment assessment =
        evaluator.evaluate(
            recognizedVisitor(
                List.of(
                    Signal.URGENCY, Signal.PRESSURE, Signal.PROLONGED_PRESENCE, Signal.LONG_STOP)));

    assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertTrue(
        assessment.getMessage().startsWith(KnownPersonAttention.URGENCY_AND_PRESSURE_MESSAGE));
  }

  private static List<Signal> recognizedVisitor(List<Signal> requestSignals) {
    List<Signal> signals = new ArrayList<>(List.of(Signal.KNOWN_PERSON, Signal.NO_THREAT_OBJECT));
    signals.addAll(requestSignals);
    return signals;
  }

  private static Stream<Arguments> requestsNeedingAttention() {
    return Stream.of(
        Arguments.of(
            List.of(Signal.URGENCY),
            "Alguien conocido podría necesitar ayuda. Revisá la situación."),
        Arguments.of(
            List.of(Signal.PRESSURE),
            "Persona reconocida que ejerce presión para que se actúe; conviene revisar la situación"),
        Arguments.of(
            List.of(Signal.URGENCY, Signal.PRESSURE),
            "Persona reconocida con señales de urgencia que ejerce presión; podría necesitar ayuda"));
  }

  private static Stream<List<Signal>> otherSuspiciousPatterns() {
    return Stream.of(
        List.of(Signal.AUTHORITY, Signal.CODE_REQUEST),
        List.of(Signal.AUTHORITY, Signal.VERIFICATION_AVOIDANCE),
        List.of(Signal.MONEY_REQUEST, Signal.URGENCY),
        List.of(Signal.MONEY_REQUEST, Signal.PRESSURE),
        List.of(Signal.AUTHORITY, Signal.URGENCY),
        List.of(Signal.COVERED_FACE),
        List.of(Signal.MULTIPLE_PEOPLE),
        List.of(Signal.CODE_REQUEST),
        List.of(Signal.VERIFICATION_AVOIDANCE),
        List.of(Signal.LOITERING, Signal.PROLONGED_PRESENCE, Signal.REPEATED_DIRECTION_CHANGES),
        List.of(Signal.UNKNOWN_PERSON, Signal.FAST_APPROACH));
  }
}
