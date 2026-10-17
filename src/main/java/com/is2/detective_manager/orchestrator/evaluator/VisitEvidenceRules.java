package com.is2.detective_manager.orchestrator.evaluator;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Combina evidencias de la visita sin atribuirlas a una misma persona ni afirmar simultaneidad.
 * Dentro de SOSPECHOSO, las combinaciones físicas se muestran antes que las de conversación. Estas
 * explicaciones de visita preceden a las genéricas, que pueden vincular identidad y conducta.
 */
public final class VisitEvidenceRules {

  private static final List<Rule> RULES =
      List.of(
          rule(
              850,
              "Verificá la identidad y el motivo del pedido antes de pagar.",
              Signal.AUTHORITY,
              Signal.MONEY_REQUEST),
          rule(
              840,
              "Hay presión para que actúes. Confirmá quién hace el pedido antes de decidir.",
              Signal.AUTHORITY,
              Signal.PRESSURE),
          rule(
              860,
              "Cuidado con el pedido de dinero: falta verificar quién lo solicita.",
              Signal.MONEY_REQUEST,
              Signal.VERIFICATION_AVOIDANCE),
          rule(
              920,
              "Durante la visita se detectaron merodeo y evasión de verificación",
              Signal.LOITERING,
              Signal.VERIFICATION_AVOIDANCE),
          rule(
              930,
              "Rostro cubierto y actividad inusual en la entrada. Verificá quién está afuera.",
              Signal.COVERED_FACE,
              Signal.LOITERING),
          rule(
              940,
              "Rostro cubierto: no permite ver bien quién es. Revisá antes de abrir.",
              Signal.COVERED_FACE,
              Signal.FAST_APPROACH),
          rule(
              900,
              "Varias personas en la entrada y presión para que actúes. Revisá antes de decidir.",
              Signal.MULTIPLE_PEOPLE,
              Signal.PRESSURE),
          rule(
              910,
              "Durante la visita se detectaron varias personas y comportamiento de merodeo",
              Signal.MULTIPLE_PEOPLE,
              Signal.LOITERING));

  private VisitEvidenceRules() {}

  static List<Rule> rules() {
    return RULES;
  }

  public static boolean matchesAny(Set<Signal> signals) {
    return RULES.stream().anyMatch(rule -> rule.matches(signals));
  }

  private static Rule rule(int messagePriority, String message, Signal first, Signal second) {
    return Rule.builder()
        .requires(EnumSet.of(first, second))
        .severity(Severity.SOSPECHOSO)
        .verdict(Verdict.CONTINUE)
        .messagePriority(messagePriority)
        .message(message)
        .build();
  }
}
