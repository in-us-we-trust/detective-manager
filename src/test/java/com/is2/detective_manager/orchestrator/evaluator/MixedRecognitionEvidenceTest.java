package com.is2.detective_manager.orchestrator.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.is2.detective_manager.mapper.TrackAppCode;
import com.is2.detective_manager.mapper.TrackDisplay;
import com.is2.detective_manager.mapper.TrackMessageBuilder;
import com.is2.detective_manager.orchestrator.Verdict;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class MixedRecognitionEvidenceTest {

  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();

  @Test
  void mixedRecognitionCannotConcludeSafetyRegardlessOfSignalOrderOrObjectReassurance() {
    for (boolean noThreatObject : List.of(true, false)) {
      List<Signal> signals = mixedRecognition();
      if (noThreatObject) {
        signals.add(Signal.NO_THREAT_OBJECT);
      }

      Assessment assessment = evaluator.evaluate(signals);
      Collections.reverse(signals);

      assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
      assertEquals(Verdict.CONTINUE, assessment.getVerdict());
      assertEquals(AssessmentStatus.EVALUATING, assessment.getStatus());
      assertEquals(MixedRecognitionEvidence.MESSAGE, assessment.getMessage());
      assertEquals(assessment, evaluator.evaluate(signals));
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"URGENCY", "PRESSURE", "TOOL_DETECTED", "LADDER_DETECTED", "PACKAGE_DETECTED"})
  void mildOrInformativeFindingsDoNotRestoreSafeOrReceiveTheHelpException(Signal finding) {
    List<Signal> signals = mixedRecognition();
    signals.add(Signal.NO_THREAT_OBJECT);
    signals.add(finding);

    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertTrue(assessment.getMessage().startsWith(MixedRecognitionEvidence.MESSAGE));
    assertFalse(assessment.getMessage().contains("podría necesitar ayuda"));
    assertFalse(assessment.getMessage().contains("conviene revisar la situación"));
  }

  @ParameterizedTest
  @MethodSource("suspiciousFindings")
  void suspiciousFindingsKeepTheirSeverityAndLeadTheMessage(List<Signal> findings) {
    List<Signal> signals = mixedRecognition();
    signals.add(Signal.NO_THREAT_OBJECT);
    signals.addAll(findings);
    List<Signal> unknownOnly = new ArrayList<>(signals);
    unknownOnly.remove(Signal.KNOWN_PERSON);
    String expectedTitle = evaluator.evaluate(unknownOnly).getMessage().split(" · ")[0];

    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertTrue(assessment.getMessage().startsWith(expectedTitle));
    assertTrue(assessment.getMessage().contains(MixedRecognitionEvidence.MESSAGE));
    assertFalse(assessment.getMessage().contains("podría necesitar ayuda"));
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"WEAPON_DETECTED", "THREAT"})
  void criticalFindingsStillConcludeDanger(Signal critical) {
    List<Signal> signals = mixedRecognition();
    signals.add(Signal.NO_THREAT_OBJECT);
    signals.add(critical);

    Assessment assessment = evaluator.evaluate(signals);

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

  @Test
  void historyExplainsMixedRecognitionUsingTheExistingCode() {
    TrackDisplay display =
        new TrackMessageBuilder(evaluator).build("KNOWN_PERSON,UNKNOWN_PERSON,NO_THREAT_OBJECT");

    assertEquals(TrackAppCode.KNOWN_PERSON, display.code());
    assertEquals(MixedRecognitionEvidence.MESSAGE, display.title());
  }

  private List<Signal> mixedRecognition() {
    return new ArrayList<>(List.of(Signal.KNOWN_PERSON, Signal.UNKNOWN_PERSON));
  }

  private static Stream<Arguments> suspiciousFindings() {
    return Stream.of(
        Arguments.of(List.of(Signal.LOITERING)),
        Arguments.of(List.of(Signal.FAST_APPROACH)),
        Arguments.of(List.of(Signal.COVERED_FACE)),
        Arguments.of(List.of(Signal.MULTIPLE_PEOPLE)),
        Arguments.of(List.of(Signal.VERIFICATION_AVOIDANCE)),
        Arguments.of(List.of(Signal.AUTHORITY, Signal.MONEY_REQUEST)),
        Arguments.of(List.of(Signal.COVERED_FACE, Signal.FAST_APPROACH)),
        Arguments.of(List.of(Signal.URGENCY, Signal.PRESSURE)));
  }
}
