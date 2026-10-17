package com.is2.detective_manager.orchestrator.evaluator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.is2.detective_manager.mapper.TrackAppCode;
import com.is2.detective_manager.mapper.TrackDisplay;
import com.is2.detective_manager.mapper.TrackMessageBuilder;
import com.is2.detective_manager.orchestrator.Verdict;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuleBasedDangerEvaluatorPriorityTest {

  private static final String COVERED_FAST =
      "Rostro cubierto: no permite ver bien quién es. Revisá antes de abrir.";
  private static final String COVERED_LOITERING =
      "Rostro cubierto y actividad inusual en la entrada. Verificá quién está afuera.";
  private static final String LOITERING_VERIFICATION =
      "Durante la visita se detectaron merodeo y evasión de verificación";
  private static final String GROUP_LOITERING =
      "Durante la visita se detectaron varias personas y comportamiento de merodeo";
  private static final String AUTHORITY_MONEY =
      "Verificá la identidad y el motivo del pedido antes de pagar.";

  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();

  @ParameterizedTest
  @MethodSource("competingFindings")
  void competingSuspiciousFindings_haveAnExplicitTitleAndOrderedDetails(
      List<Signal> signals, String expectedMessage) {
    Assessment assessment = evaluator.evaluate(signals);
    List<Signal> reversed = new ArrayList<>(signals);
    Collections.reverse(reversed);

    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertEquals(AssessmentStatus.EVALUATING, assessment.getStatus());
    assertEquals(expectedMessage, assessment.getMessage());
    assertEquals(assessment, evaluator.evaluate(reversed));
  }

  @Test
  void manyFindings_keepTheHighestPriorityEvidenceWithinTheMessageLimit() {
    List<Signal> signals = manyFindings();

    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(
        String.join(
            " · ", COVERED_FAST, COVERED_LOITERING, LOITERING_VERIFICATION, GROUP_LOITERING),
        assessment.getMessage());
    assertEquals(4, assessment.getMessage().split(" · ").length);
    assertEquals(Severity.SOSPECHOSO, assessment.getSeverity());
    assertEquals(Verdict.CONTINUE, assessment.getVerdict());
    assertFalse(assessment.getMessage().contains("podría necesitar ayuda"));
    assertFalse(assessment.getMessage().contains("Persona desconocida acercándose"));
  }

  @Test
  void bothCriticalFindings_precedeAllSuspiciousDetails() {
    List<Signal> signals = new ArrayList<>(manyFindings());
    signals.add(Signal.WEAPON_DETECTED);
    signals.add(Signal.THREAT);

    Assessment assessment = evaluator.evaluate(signals);

    assertEquals(Severity.PELIGROSO, assessment.getSeverity());
    assertEquals(Verdict.DANGER, assessment.getVerdict());
    assertEquals(AssessmentStatus.CONCLUDED, assessment.getStatus());
    assertEquals(
        String.join(
            " · ",
            "La persona está armada",
            "Amenaza verbal detectada",
            COVERED_FAST,
            COVERED_LOITERING),
        assessment.getMessage());
  }

  @Test
  void historyUsesTheSamePriorityAndKeepsTheKnownPersonCode() {
    List<Signal> signals = manyFindings();
    String csv = String.join(",", signals.stream().map(Enum::name).toList());

    TrackDisplay display = new TrackMessageBuilder(evaluator).build(csv);

    assertEquals(TrackAppCode.KNOWN_PERSON, display.code());
    assertEquals(evaluator.evaluate(signals).getMessage(), display.title());
  }

  @Test
  void sortingUsesExplicitPriorityRegardlessOfDeclarationOrderOrConditionCount() {
    Rule moreConditions =
        rule(
            Severity.SOSPECHOSO,
            Verdict.CONTINUE,
            1,
            Signal.AUTHORITY,
            Signal.MONEY_REQUEST,
            Signal.URGENCY);
    Rule higherPriority = rule(Severity.SOSPECHOSO, Verdict.CONTINUE, 2, Signal.COVERED_FACE);
    Rule critical = rule(Severity.PELIGROSO, Verdict.DANGER, 1, Signal.THREAT);
    List<Rule> expected = List.of(critical, higherPriority, moreConditions);

    assertEquals(
        expected,
        RuleBasedDangerEvaluator.sortByPriority(List.of(moreConditions, higherPriority, critical)));
    assertEquals(
        expected,
        RuleBasedDangerEvaluator.sortByPriority(List.of(critical, higherPriority, moreConditions)));
  }

  @Test
  void duplicatePrioritiesWithinALevel_areRejectedInsteadOfUsingDeclarationOrder() {
    Rule first = rule(Severity.SOSPECHOSO, Verdict.CONTINUE, 1, Signal.COVERED_FACE);
    Rule second = rule(Severity.SOSPECHOSO, Verdict.CONTINUE, 1, Signal.LOITERING);

    assertThrows(
        IllegalArgumentException.class,
        () -> RuleBasedDangerEvaluator.sortByPriority(List.of(first, second)));
  }

  private static List<Signal> manyFindings() {
    return List.of(
        Signal.AUTHORITY,
        Signal.MONEY_REQUEST,
        Signal.PRESSURE,
        Signal.VERIFICATION_AVOIDANCE,
        Signal.LOITERING,
        Signal.COVERED_FACE,
        Signal.FAST_APPROACH,
        Signal.MULTIPLE_PEOPLE,
        Signal.KNOWN_PERSON,
        Signal.NO_THREAT_OBJECT);
  }

  private static Rule rule(Severity severity, Verdict verdict, int priority, Signal... signals) {
    return Rule.builder()
        .requires(EnumSet.copyOf(List.of(signals)))
        .severity(severity)
        .verdict(verdict)
        .messagePriority(priority)
        .message("Test")
        .build();
  }

  private static Stream<Arguments> competingFindings() {
    return Stream.of(
        Arguments.of(
            List.of(
                Signal.AUTHORITY, Signal.MONEY_REQUEST, Signal.COVERED_FACE, Signal.FAST_APPROACH),
            COVERED_FAST + " · " + AUTHORITY_MONEY),
        Arguments.of(
            List.of(Signal.AUTHORITY, Signal.PRESSURE, Signal.COVERED_FACE, Signal.LOITERING),
            COVERED_LOITERING
                + " · Hay presión para que actúes. Confirmá quién hace el pedido antes de decidir."),
        Arguments.of(
            List.of(Signal.COVERED_FACE, Signal.LOITERING, Signal.FAST_APPROACH),
            COVERED_FAST + " · " + COVERED_LOITERING),
        Arguments.of(
            List.of(
                Signal.AUTHORITY,
                Signal.MONEY_REQUEST,
                Signal.LOITERING,
                Signal.VERIFICATION_AVOIDANCE),
            LOITERING_VERIFICATION
                + " · Cuidado con el pedido de dinero: falta verificar quién lo solicita."
                + " · "
                + AUTHORITY_MONEY),
        Arguments.of(
            List.of(Signal.MULTIPLE_PEOPLE, Signal.LOITERING, Signal.PRESSURE),
            GROUP_LOITERING
                + " · Varias personas en la entrada y presión para que actúes. Revisá antes de decidir."),
        Arguments.of(
            List.of(
                Signal.AUTHORITY,
                Signal.MONEY_REQUEST,
                Signal.VERIFICATION_AVOIDANCE,
                Signal.PRESSURE,
                Signal.URGENCY),
            "Cuidado con el pedido de dinero: falta verificar quién lo solicita."
                + " · "
                + AUTHORITY_MONEY
                + " · Hay presión para que actúes. Confirmá quién hace el pedido antes de decidir."
                + " · Habla con urgencia"),
        Arguments.of(
            List.of(Signal.LOITERING, Signal.UNKNOWN_PERSON, Signal.FAST_APPROACH),
            "Hay alguien desconocido y un acercamiento rápido en la entrada. Revisá antes de abrir."
                + " · Persona desconocida con posible comportamiento de merodeo"),
        Arguments.of(
            List.of(Signal.AUTHORITY, Signal.CODE_REQUEST, Signal.VERIFICATION_AVOIDANCE),
            "Dice ser autoridad y pide un código de acceso"
                + " · Dice ser autoridad pero evita identificarse"));
  }
}
