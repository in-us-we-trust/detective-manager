package com.is2.detective_manager.orchestrator.evaluator;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RuleBasedDangerEvaluator implements DangerEvaluator {

  // Reglas agrupadas por tema para leerlas fácil. El ORDEN REAL lo da PRIORITY (se ordenan solas),
  // el orden de escritura solo desempata reglas del mismo nivel y la misma cantidad de señales.
  private static final List<Rule> DECLARED_RULES =
          List.of(
                  // ===================== PELIGROSO (DANGER) =====================
                  rule(Severity.PELIGROSO, Verdict.DANGER, "La persona está armada", Signal.WEAPON_DETECTED),
                  rule(Severity.PELIGROSO, Verdict.DANGER, "Amenaza verbal detectada", Signal.THREAT),

                  // ===================== SOSPECHOSO: video =====================
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Persona desconocida con posible comportamiento de merodeo",
                          Signal.LOITERING,
                          Signal.UNKNOWN_PERSON),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Persona desconocida acercándose rápidamente",
                          Signal.FAST_APPROACH,
                          Signal.UNKNOWN_PERSON),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Permanencia prolongada con movimientos de ida y vuelta",
                          Signal.PROLONGED_PRESENCE,
                          Signal.RETURNED_TO_PREVIOUS_POSITION),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Permanencia prolongada con detención extensa",
                          Signal.PROLONGED_PRESENCE,
                          Signal.LONG_STOP),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Posible comportamiento de merodeo",
                          Signal.LOITERING),

                  // ===================== SOSPECHOSO: conversación (combinaciones) =====================
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Dice ser autoridad y pide un código de acceso",
                          Signal.AUTHORITY,
                          Signal.CODE_REQUEST),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Dice ser autoridad pero evita identificarse",
                          Signal.AUTHORITY,
                          Signal.VERIFICATION_AVOIDANCE),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Pide dinero con urgencia",
                          Signal.MONEY_REQUEST,
                          Signal.URGENCY),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Pide dinero bajo presión",
                          Signal.MONEY_REQUEST,
                          Signal.PRESSURE),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Posible manipulación: autoridad + urgencia",
                          Signal.AUTHORITY,
                          Signal.URGENCY),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Presiona para que se actúe con urgencia",
                          Signal.PRESSURE,
                          Signal.URGENCY),

                  // ===================== SOSPECHOSO: imagen =====================
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Persona desconocida con la cara tapada",
                          Signal.COVERED_FACE,
                          Signal.UNKNOWN_PERSON),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Persona con la cara tapada",
                          Signal.COVERED_FACE),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Varias personas en la puerta, al menos una desconocida",
                          Signal.MULTIPLE_PEOPLE,
                          Signal.UNKNOWN_PERSON),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Se detectaron varias personas en la puerta",
                          Signal.MULTIPLE_PEOPLE),

                  // ===================== SOSPECHOSO: conversación (sueltas) =====================
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Pide un código de acceso",
                          Signal.CODE_REQUEST),
                  rule(
                          Severity.SOSPECHOSO,
                          Verdict.CONTINUE,
                          "Evita identificarse",
                          Signal.VERIFICATION_AVOIDANCE),

                  // ===================== SAFE =====================
                  // Persona reconocida Y sin objetos amenazantes: se confirma seguro.
                  rule(
                          Severity.NORMAL,
                          Verdict.SAFE,
                          "Persona reconocida",
                          Signal.KNOWN_PERSON,
                          Signal.NO_THREAT_OBJECT),

                  // ===================== LLAMATIVO: combinaciones leves =====================
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "Persona desconocida con movimientos erráticos",
                          Signal.REPEATED_DIRECTION_CHANGES,
                          Signal.UNKNOWN_PERSON),
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "Persona desconocida acercándose",
                          Signal.APPROACHING_CAMERA,
                          Signal.UNKNOWN_PERSON),

                  // ===================== LLAMATIVO: video (sueltas) =====================
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "Persona presente durante un período prolongado",
                          Signal.PROLONGED_PRESENCE),
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "Alguien se acerca rápidamente a la puerta",
                          Signal.FAST_APPROACH),
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "Movimientos erráticos frente a la puerta",
                          Signal.REPEATED_DIRECTION_CHANGES),
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "Se alejó y volvió a acercarse a la puerta",
                          Signal.RETURNED_TO_PREVIOUS_POSITION),
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "Persona detenida frente a la puerta",
                          Signal.LONG_STOP),

                  // ===================== LLAMATIVO: conversación (sueltas) =====================
                  rule(Severity.LLAMATIVO, Verdict.CONTINUE, "Habla con urgencia", Signal.URGENCY),
                  rule(Severity.LLAMATIVO, Verdict.CONTINUE, "Se presenta como autoridad", Signal.AUTHORITY),
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "Ejerce presión para que se abra",
                          Signal.PRESSURE),
                  rule(Severity.LLAMATIVO, Verdict.CONTINUE, "Pide dinero", Signal.MONEY_REQUEST),

                  // ===================== LLAMATIVO: desconocido =====================
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "No se reconoce a la persona. No se detectaron objetos peligrosos",
                          Signal.UNKNOWN_PERSON,
                          Signal.NO_THREAT_OBJECT),
                  rule(
                          Severity.LLAMATIVO,
                          Verdict.CONTINUE,
                          "No se reconoce a la persona",
                          Signal.UNKNOWN_PERSON),

                  // ===================== NORMAL: informativas =====================
                  rule(Severity.NORMAL, Verdict.CONTINUE, "Persona reconocida", Signal.KNOWN_PERSON),
                  rule(
                          Severity.NORMAL,
                          Verdict.CONTINUE,
                          "Persona acercándose a la puerta",
                          Signal.APPROACHING_CAMERA),
                  rule(
                          Severity.NORMAL,
                          Verdict.CONTINUE,
                          "Persona detectada, sin objetos peligrosos",
                          Signal.PERSON,
                          Signal.NO_THREAT_OBJECT),
                  rule(Severity.NORMAL, Verdict.CONTINUE, "Se detectó una persona", Signal.PERSON),
                  rule(
                          Severity.NORMAL,
                          Verdict.CONTINUE,
                          "No se detectaron objetos peligrosos",
                          Signal.NO_THREAT_OBJECT),
                  rule(
                          Severity.NORMAL,
                          Verdict.CONTINUE,
                          "No se detectó nada extraño en la conversación",
                          Signal.NO_SUSPICIOUS_CONVERSATION),
                  rule(
                          Severity.NORMAL,
                          Verdict.CONTINUE,
                          "No se detectó nada extraño en el video",
                          Signal.NO_SUSPICIOUS_BEHAVIOR));

  /** Cuántos mensajes extra se agregan al título como máximo (para no armar un texto eterno). */
  static final int MAX_EXTRA_DETAILS = 3;

  static final String SEPARATOR = " · ";

  /**
   * Nivel de prioridad de una regla. SAFE va por debajo de SOSPECHOSO (una persona conocida con
   * conducta sospechosa no debe quedar tapada por el "seguro") pero por encima de LLAMATIVO.
   */
  private static int level(Rule rule) {
    if (rule.getVerdict() == Verdict.DANGER) {
      return 5;
    }
    if (rule.getSeverity() == Severity.SOSPECHOSO) {
      return 4;
    }
    if (rule.getVerdict() == Verdict.SAFE) {
      return 3;
    }
    return rule.getSeverity() == Severity.LLAMATIVO ? 2 : 1;
  }

  /** Nivel desc, cantidad de señales desc, orden de escritura asc. */
  private static final List<Rule> RULES = sortByPriority(DECLARED_RULES);

  static List<Rule> sortByPriority(List<Rule> declared) {
    return IntStream.range(0, declared.size())
            .boxed()
            .sorted(
                    Comparator.<Integer>comparingInt(i -> -level(declared.get(i)))
                            .thenComparingInt(i -> -declared.get(i).getRequires().size())
                            .thenComparingInt(i -> i))
            .map(declared::get)
            .toList();
  }

  // Si ninguna regla matchea (no hubo ninguna señal): igual se informa que se analizó.
  private static final Assessment DEFAULT_ASSESSMENT =
          Assessment.builder()
                  .status(AssessmentStatus.EVALUATING)
                  .severity(Severity.NORMAL)
                  .message("Análisis realizado: sin señales relevantes")
                  .verdict(Verdict.CONTINUE)
                  .build();

  private static Rule rule(
          Severity severity, Verdict verdict, String message, Signal first, Signal... rest) {
    return Rule.builder()
            .requires(EnumSet.of(first, rest))
            .severity(severity)
            .verdict(verdict)
            .message(message)
            .build();
  }

  @Override
  public Assessment evaluate(List<Signal> accumulatedSignals) {
    Set<Signal> present =
            accumulatedSignals.isEmpty()
                    ? EnumSet.noneOf(Signal.class)
                    : EnumSet.copyOf(accumulatedSignals);
    // "Sin objetos amenazantes" no vale si en el track ya hubo arma o cara tapada.
    if (present.contains(Signal.WEAPON_DETECTED) || present.contains(Signal.COVERED_FACE)) {
      present.remove(Signal.NO_THREAT_OBJECT);
    }
    // Lo mismo para conversación y video: "nada extraño" no vale si otro chunk del track sí
    // detectó algo de esa fuente.
    if (!Collections.disjoint(present, CONVERSATION_FINDINGS)) {
      present.remove(Signal.NO_SUSPICIOUS_CONVERSATION);
    }
    if (!Collections.disjoint(present, VIDEO_FINDINGS)) {
      present.remove(Signal.NO_SUSPICIOUS_BEHAVIOR);
    }

    List<Rule> matched = RULES.stream().filter(r -> r.matches(present)).toList();
    if (matched.isEmpty()) {
      log.info("Ninguna regla matcheó para señales={} -> default", present);
      return DEFAULT_ASSESSMENT;
    }

    Rule winner = matched.get(0);
    String message = composeMessage(winner, matched);
    log.info("Regla matcheó: requires={} -> '{}'", winner.getRequires(), message);
    return Assessment.builder()
            .status(
                    winner.getVerdict() == Verdict.CONTINUE
                            ? AssessmentStatus.EVALUATING
                            : AssessmentStatus.CONCLUDED)
            .severity(winner.getSeverity())
            .message(message)
            .verdict(winner.getVerdict())
            .build();
  }

  /**
   * Título = mensaje de la regla ganadora. Después se recorren las demás reglas que matchearon (ya
   * vienen en orden de prioridad) y se suma el mensaje de cada una que aporte al menos una señal
   * todavía no cubierta. PERSON y NO_THREAT_OBJECT solas no justifican un extra (son relleno).
   */
  static String composeMessage(Rule winner, List<Rule> matched) {
    Set<Signal> covered = EnumSet.copyOf(winner.getRequires());
    List<String> parts = new ArrayList<>();
    parts.add(winner.getMessage());

    for (Rule rule : matched) {
      if (parts.size() > MAX_EXTRA_DETAILS) {
        break;
      }
      if (rule == winner || parts.contains(rule.getMessage())) {
        continue;
      }
      boolean addsInfo =
              rule.getRequires().stream().anyMatch(s -> !covered.contains(s) && !LOW_INFO.contains(s));
      if (addsInfo) {
        parts.add(rule.getMessage());
        covered.addAll(rule.getRequires());
      }
    }
    return String.join(SEPARATOR, parts);
  }

  /** Señales que solo acompañan: no generan un mensaje extra por sí mismas. */
  private static final Set<Signal> LOW_INFO = EnumSet.of(Signal.PERSON, Signal.NO_THREAT_OBJECT);

  /** Señales de conversación que invalidan NO_SUSPICIOUS_CONVERSATION. */
  private static final Set<Signal> CONVERSATION_FINDINGS =
          EnumSet.of(
                  Signal.URGENCY,
                  Signal.AUTHORITY,
                  Signal.PRESSURE,
                  Signal.VERIFICATION_AVOIDANCE,
                  Signal.THREAT,
                  Signal.CODE_REQUEST,
                  Signal.MONEY_REQUEST);

  /**
   * Señales de video que invalidan NO_SUSPICIOUS_BEHAVIOR. MULTIPLE_PEOPLE queda afuera porque
   * también la emite el step de imagen.
   */
  private static final Set<Signal> VIDEO_FINDINGS =
          EnumSet.of(
                  Signal.PROLONGED_PRESENCE,
                  Signal.LONG_STOP,
                  Signal.REPEATED_DIRECTION_CHANGES,
                  Signal.RETURNED_TO_PREVIOUS_POSITION,
                  Signal.APPROACHING_CAMERA,
                  Signal.FAST_APPROACH,
                  Signal.LOITERING);
}