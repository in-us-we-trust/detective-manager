package com.is2.detective_manager.orchestrator.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class VisitEvidenceRulesTest {

  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();
  private final TrackMessageBuilder messageBuilder = new TrackMessageBuilder(evaluator);

  @ParameterizedTest
  @MethodSource("visitPatterns")
  void visitPattern_isSuspiciousAndExplainedWithoutAttributingAnIdentity(
      List<Signal> signals, String expectedMessage) {
    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(AssessmentStatus.EVALUATING, assessment.getStatus());
    assertEquals(expectedMessage, assessment.getMessage());
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"AUTHORITY", "MONEY_REQUEST", "PRESSURE"})
  void individualConversationFindings_remainMild(Signal signal) {
    Assessment assessment = evaluator.evaluate(List.of(signal));

    assertEquals(Severity.LLAMATIVO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
  }

  @ParameterizedTest
  @MethodSource("visitPatterns")
  void visitPattern_isNotHiddenBySafeForARecognizedVisitor(
      List<Signal> pattern, String expectedMessage) {
    List<Signal> signals = new ArrayList<>(pattern);
    signals.add(Signal.KNOWN_PERSON);
    signals.add(Signal.NO_THREAT_OBJECT);

    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertTrue(assessment.getMessage().startsWith(expectedMessage));
    assertFalse(assessment.getMessage().contains("podría necesitar ayuda"));
    assertFalse(assessment.getMessage().contains("conviene revisar la situación"));
  }

  @ParameterizedTest
  @MethodSource("visitPatterns")
  void criticalFindings_alwaysKeepPriorityOverVisitPatterns(
      List<Signal> pattern, String ignoredMessage) {
    for (Signal critical : List.of(Signal.WEAPON_DETECTED, Signal.THREAT)) {
      List<Signal> signals = new ArrayList<>(pattern);
      signals.add(critical);
      signals.add(Signal.KNOWN_PERSON);
      signals.add(Signal.NO_THREAT_OBJECT);

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
      assertFalse(assessment.getMessage().contains("podría necesitar ayuda"));
    }
  }

  @ParameterizedTest
  @MethodSource("visitPatterns")
  void historyShowsTheVisitPatternAndKeepsExistingPresentationCodes(
      List<Signal> pattern, String expectedMessage) {
    for (TrackAppCode code :
        List.of(TrackAppCode.ACTIVITY, TrackAppCode.KNOWN_PERSON, TrackAppCode.UNKNOWN_PERSON)) {
      List<Signal> signals = new ArrayList<>(pattern);
      if (code == TrackAppCode.KNOWN_PERSON) {
        signals.add(Signal.KNOWN_PERSON);
        signals.add(Signal.NO_THREAT_OBJECT);
      } else if (code == TrackAppCode.UNKNOWN_PERSON) {
        signals.add(Signal.UNKNOWN_PERSON);
      }
      String csv = String.join(",", signals.stream().map(Enum::name).toList());

      TrackDisplay display = messageBuilder.build(csv);

      assertEquals(code, display.code());
      assertEquals(evaluator.evaluate(signals).getMessage(), display.title());
      assertTrue(display.title().startsWith(expectedMessage));
    }
  }

  @Test
  void additionalDetails_doNotReintroduceAnUnprovenIdentityForTheMovement() {
    for (List<Signal> findings :
        List.of(
            List.of(
                Signal.COVERED_FACE,
                Signal.FAST_APPROACH,
                Signal.APPROACHING_CAMERA,
                Signal.UNKNOWN_PERSON),
            List.of(Signal.COVERED_FACE, Signal.LOITERING, Signal.UNKNOWN_PERSON))) {
      Assessment assessment = evaluator.evaluate(findings);

      assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
      assertTrue(assessment.getMessage().startsWith("Rostro cubierto"));
      assertTrue(assessment.getMessage().contains("No se reconoce a la persona"));
      assertFalse(assessment.getMessage().contains("Persona desconocida acercándose"));
      assertFalse(
          assessment.getMessage().contains("Persona desconocida con posible comportamiento"));
    }
  }

  @Test
  void existingCodeRequestRules_keepTheirBehaviorWithoutAddingNewCombinations() {
    Assessment code = evaluator.evaluate(List.of(Signal.CODE_REQUEST));
    Assessment authorityAndCode =
        evaluator.evaluate(List.of(Signal.AUTHORITY, Signal.CODE_REQUEST));

    assertEquals(Severity.SOSPECHOSO, code.getSeverity());
    assertEquals("Pide un código de acceso", code.getMessage());
    assertEquals(Verdict.CONTINUE, code.getVerdict());
    assertEquals(Severity.SOSPECHOSO, authorityAndCode.getSeverity());
    assertEquals("Dice ser autoridad y pide un código de acceso", authorityAndCode.getMessage());
    assertEquals(Verdict.CONTINUE, authorityAndCode.getVerdict());
  }

  private static Stream<Arguments> visitPatterns() {
    return Stream.of(
        Arguments.of(
            List.of(Signal.AUTHORITY, Signal.MONEY_REQUEST),
            "Verificá la identidad y el motivo del pedido antes de pagar."),
        Arguments.of(
            List.of(Signal.AUTHORITY, Signal.PRESSURE),
            "Hay presión para que actúes. Confirmá quién hace el pedido antes de decidir."),
        Arguments.of(
            List.of(Signal.MONEY_REQUEST, Signal.VERIFICATION_AVOIDANCE),
            "Cuidado con el pedido de dinero: falta verificar quién lo solicita."),
        Arguments.of(
            List.of(Signal.LOITERING, Signal.VERIFICATION_AVOIDANCE),
            "Durante la visita se detectaron merodeo y evasión de verificación"),
        Arguments.of(
            List.of(Signal.COVERED_FACE, Signal.LOITERING),
            "Rostro cubierto y actividad inusual en la entrada. Verificá quién está afuera."),
        Arguments.of(
            List.of(Signal.COVERED_FACE, Signal.FAST_APPROACH),
            "Rostro cubierto: no permite ver bien quién es. Revisá antes de abrir."),
        Arguments.of(
            List.of(Signal.MULTIPLE_PEOPLE, Signal.PRESSURE),
            "Varias personas en la entrada y presión para que actúes. Revisá antes de decidir."),
        Arguments.of(
            List.of(Signal.MULTIPLE_PEOPLE, Signal.LOITERING),
            "Durante la visita se detectaron varias personas y comportamiento de merodeo"));
  }
}
