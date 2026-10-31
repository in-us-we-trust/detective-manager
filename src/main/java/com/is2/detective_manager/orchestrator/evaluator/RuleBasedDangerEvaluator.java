package com.is2.detective_manager.orchestrator.evaluator;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RuleBasedDangerEvaluator implements DangerEvaluator {

  public static final String VISIT_PRESENCE_MESSAGE =
      "Atención: presencia prolongada inusual en la entrada. Revisá la situación.";

  // Primero manda la gravedad; después, la prioridad explícita del mensaje.
  // El orden de escritura y la cantidad de señales no deciden qué se muestra primero.
  // SOSPECHOSO: 900+ combinaciones físicas de visita; 840-860 conversación de visita;
  // 790 patrones anteriores de video; 710-760 conversación anterior; 630-690 indicios
  // generales.
  // Las combinaciones de visita preceden a las anteriores para conservar su alcance sin atribuir
  // evidencias de distintos análisis a una misma persona.
  private static final List<Rule> DECLARED_RULES =
      List.of(
          // ===================== PELIGROSO (DANGER) =====================
          rule(
              20,
              Severity.PELIGROSO,
              Verdict.DANGER,
              "La persona está armada",
              Signal.WEAPON_DETECTED),
          rule(10, Severity.PELIGROSO, Verdict.DANGER, "Amenaza verbal detectada", Signal.THREAT),

          // ===================== SOSPECHOSO: video =====================
          rule(
              790,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Persona desconocida con posible comportamiento de merodeo",
              Signal.LOITERING,
              Signal.UNKNOWN_PERSON),
          rule(
              650,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Posible comportamiento de merodeo",
              Signal.LOITERING),

          // ===================== SOSPECHOSO: conversación (combinaciones) =====================
          rule(
              760,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Dice ser autoridad y pide un código de acceso",
              Signal.AUTHORITY,
              Signal.CODE_REQUEST),
          rule(
              750,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Dice ser autoridad pero evita identificarse",
              Signal.AUTHORITY,
              Signal.VERIFICATION_AVOIDANCE),
          rule(
              730,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Pide dinero con urgencia",
              Signal.MONEY_REQUEST,
              Signal.URGENCY),
          rule(
              740,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Hay presión en la conversación. Verificá el pedido de dinero antes de pagar.",
              Signal.MONEY_REQUEST,
              Signal.PRESSURE),
          rule(
              720,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Verificá la identidad antes de actuar por una urgencia.",
              Signal.AUTHORITY,
              Signal.URGENCY),
          rule(
              710,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Presiona para que se actúe con urgencia",
              Signal.PRESSURE,
              Signal.URGENCY),

          // ===================== SOSPECHOSO: imagen =====================
          rule(
              690,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Rostro cubierto: no permite ver bien quién es. Revisá antes de abrir.",
              Signal.COVERED_FACE,
              Signal.UNKNOWN_PERSON),
          rule(
              680,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Rostro cubierto: no permite ver bien quién es. Revisá antes de abrir.",
              Signal.COVERED_FACE),

          // ===================== SOSPECHOSO: conversación (sueltas) =====================
          rule(
              640,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Pide un código de acceso",
              Signal.CODE_REQUEST),
          rule(
              630,
              Severity.SOSPECHOSO,
              Verdict.CONTINUE,
              "Evita identificarse",
              Signal.VERIFICATION_AVOIDANCE),

          // ===================== SAFE =====================
          // Persona reconocida Y sin objetos amenazantes. Los filtros excluyen SAFE cuando hay
          // detecciones desconocidas o cuando la persona reconocida requiere atención.
          rule(
              100,
              Severity.NORMAL,
              Verdict.SAFE,
              "Persona reconocida",
              Signal.KNOWN_PERSON,
              Signal.NO_THREAT_OBJECT),

          // ===================== LLAMATIVO: combinaciones leves =====================
          rule(
              670,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "Varias personas en la entrada. Confirmá quiénes son antes de abrir.",
              Signal.MULTIPLE_PEOPLE,
              Signal.UNKNOWN_PERSON),
          rule(
              660,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "Se detectaron varias personas en la puerta",
              Signal.MULTIPLE_PEOPLE),
          rule(
              210,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              MixedRecognitionEvidence.MESSAGE,
              Signal.KNOWN_PERSON,
              Signal.UNKNOWN_PERSON),
          rule(
              200,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              KnownPersonAttention.URGENCY_AND_PRESSURE_MESSAGE,
              Signal.KNOWN_PERSON,
              Signal.URGENCY,
              Signal.PRESSURE),
          rule(
              190,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              KnownPersonAttention.URGENCY_MESSAGE,
              Signal.KNOWN_PERSON,
              Signal.URGENCY),
          rule(
              180,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              KnownPersonAttention.PRESSURE_MESSAGE,
              Signal.KNOWN_PERSON,
              Signal.PRESSURE),
          rule(
              175,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              VISIT_PRESENCE_MESSAGE,
              Signal.PROLONGED_VISIT_PRESENCE),
          rule(
              170,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "Persona desconocida con movimientos erráticos",
              Signal.REPEATED_DIRECTION_CHANGES,
              Signal.UNKNOWN_PERSON),

          // ===================== LLAMATIVO: video (sueltas) =====================
          rule(
              150,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "Persona presente durante un período prolongado",
              Signal.PROLONGED_PRESENCE),
          rule(
              130,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "Movimientos erráticos frente a la puerta",
              Signal.REPEATED_DIRECTION_CHANGES),
          rule(
              120,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "Se detectó un regreso a una posición anterior",
              Signal.RETURNED_TO_PREVIOUS_POSITION),
          rule(
              110,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "Persona detenida frente a la puerta",
              Signal.LONG_STOP),

          // ===================== LLAMATIVO: conversación (sueltas) =====================
          rule(100, Severity.LLAMATIVO, Verdict.CONTINUE, "Habla con urgencia", Signal.URGENCY),
          rule(
              90,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "Se presenta como autoridad",
              Signal.AUTHORITY),
          rule(
              80,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "Te están presionando para que actúes. Tomate un momento para revisar la situación.",
              Signal.PRESSURE),
          rule(70, Severity.LLAMATIVO, Verdict.CONTINUE, "Pide dinero", Signal.MONEY_REQUEST),

          // ===================== LLAMATIVO: desconocido =====================
          rule(
              60,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "No se reconoce a la persona. No se detectaron objetos peligrosos",
              Signal.UNKNOWN_PERSON,
              Signal.NO_THREAT_OBJECT),
          rule(
              50,
              Severity.LLAMATIVO,
              Verdict.CONTINUE,
              "No se reconoce a la persona",
              Signal.UNKNOWN_PERSON),

          // ===================== NORMAL: informativas =====================
          rule(100, Severity.NORMAL, Verdict.CONTINUE, "Persona reconocida", Signal.KNOWN_PERSON),
          rule(
              95,
              Severity.NORMAL,
              Verdict.CONTINUE,
              "Se detectó un acercamiento rápido a la cámara",
              Signal.FAST_APPROACH),
          rule(
              90,
              Severity.NORMAL,
              Verdict.CONTINUE,
              "Persona acercándose a la puerta",
              Signal.APPROACHING_CAMERA),
          rule(
              80,
              Severity.NORMAL,
              Verdict.CONTINUE,
              "Persona detectada, sin objetos peligrosos",
              Signal.PERSON,
              Signal.NO_THREAT_OBJECT),
          rule(70, Severity.NORMAL, Verdict.CONTINUE, "Se detectó una persona", Signal.PERSON),
          // Contexto de objetos: acompaña los hallazgos, sin atribuirlos a una persona ni suponer
          // uso.
          rule(
              65,
              Severity.NORMAL,
              Verdict.CONTINUE,
              "Se detectó una escalera durante la visita",
              Signal.LADDER_DETECTED),
          rule(
              64,
              Severity.NORMAL,
              Verdict.CONTINUE,
              "Se detectó una herramienta durante la visita",
              Signal.TOOL_DETECTED),
          rule(
              63,
              Severity.NORMAL,
              Verdict.CONTINUE,
              "Se detectó un paquete durante la visita",
              Signal.PACKAGE_DETECTED),
          rule(
              60,
              Severity.NORMAL,
              Verdict.CONTINUE,
              "No se detectaron objetos peligrosos",
              Signal.NO_THREAT_OBJECT),
          rule(
              50,
              Severity.NORMAL,
              Verdict.CONTINUE,
              "No se detectó nada extraño en la conversación",
              Signal.NO_SUSPICIOUS_CONVERSATION),
          rule(
              40,
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

  /** Nivel desc y prioridad del mensaje desc, tanto para el título como para sus detalles. */
  private static final List<Rule> RULES =
      sortByPriority(
          Stream.concat(VisitEvidenceRules.rules().stream(), DECLARED_RULES.stream()).toList());

  static List<Rule> sortByPriority(List<Rule> declared) {
    List<Rule> sorted =
        declared.stream()
            .sorted(
                Comparator.comparingInt(RuleBasedDangerEvaluator::level)
                    .reversed()
                    .thenComparing(Comparator.comparingInt(Rule::getMessagePriority).reversed()))
            .toList();
    for (int i = 1; i < sorted.size(); i++) {
      Rule previous = sorted.get(i - 1);
      Rule current = sorted.get(i);
      if (level(previous) == level(current)
          && previous.getMessagePriority() == current.getMessagePriority()) {
        throw new IllegalArgumentException(
            "Las reglas del mismo nivel deben tener prioridades de mensaje distintas");
      }
    }
    return sorted;
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
      int messagePriority,
      Severity severity,
      Verdict verdict,
      String message,
      Signal first,
      Signal... rest) {
    return Rule.builder()
        .requires(EnumSet.of(first, rest))
        .severity(severity)
        .verdict(verdict)
        .messagePriority(messagePriority)
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

    // Para una persona reconocida, urgencia/presión requieren atención sin confirmar seguridad.
    // Solo se relaja su combinación genérica: las demás reglas sospechosas y críticas se conservan.
    boolean knownPersonAttention = KnownPersonAttention.appliesTo(present);
    boolean mixedRecognition = MixedRecognitionEvidence.appliesTo(present);
    List<Rule> matched =
        RULES.stream()
            .filter(r -> r.matches(present))
            .filter(r -> !isKnownPersonAttentionRule(r) || knownPersonAttention)
            // Una detección reconocida no permite cerrar como segura una visita que también
            // acumula detecciones desconocidas. Las señales se conservan sin resolver identidades.
            .filter(r -> !mixedRecognition || r.getVerdict() != Verdict.SAFE)
            .filter(
                r ->
                    !knownPersonAttention
                        || (r.getVerdict() != Verdict.SAFE
                            && !r.getRequires().equals(URGENCY_AND_PRESSURE)))
            .toList();
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

  private static boolean isKnownPersonAttentionRule(Rule rule) {
    return rule.getRequires().contains(Signal.KNOWN_PERSON)
        && (rule.getRequires().contains(Signal.URGENCY)
            || rule.getRequires().contains(Signal.PRESSURE));
  }

  /**
   * Título = mensaje de la regla ganadora. Después se recorren las demás reglas que matchearon (ya
   * vienen en orden de prioridad) y se suma el mensaje de cada una que aporte al menos una señal
   * todavía no cubierta. Las señales informativas de LOW_INFO no justifican un extra por sí solas.
   */
  static String composeMessage(Rule winner, List<Rule> matched) {
    Set<Signal> covered = EnumSet.copyOf(winner.getRequires());
    List<String> parts = new ArrayList<>();
    parts.add(winner.getMessage());
    boolean visitEvidenceContext =
        matched.stream().anyMatch(rule -> VisitEvidenceRules.rules().contains(rule));

    for (Rule rule : matched) {
      if (parts.size() > MAX_EXTRA_DETAILS) {
        break;
      }
      if (rule == winner || parts.contains(rule.getMessage())) {
        continue;
      }
      // Los detalles también deben respetar el alcance de visita. Las combinaciones anteriores
      // pueden atribuir identidad o continuidad; para esas evidencias se usan los mensajes sueltos.
      if (visitEvidenceContext
          && rule.getRequires().size() > 1
          && !VisitEvidenceRules.rules().contains(rule)
          && !rule.getRequires().equals(MixedRecognitionEvidence.SIGNALS)) {
        continue;
      }
      // Una alerta más grave conserva su explicación; no se acompaña con una hipótesis de ayuda.
      if (isKnownPersonAttentionRule(rule)
          && winner.getSeverity().getLevel() >= Severity.SOSPECHOSO.getLevel()) {
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
  private static final Set<Signal> LOW_INFO =
      EnumSet.of(
          Signal.PERSON, Signal.NO_THREAT_OBJECT, Signal.APPROACHING_CAMERA, Signal.FAST_APPROACH);

  // Solo se relaja esta combinación cuando no hay una identidad desconocida en el track.
  private static final Set<Signal> URGENCY_AND_PRESSURE =
      EnumSet.of(Signal.URGENCY, Signal.PRESSURE);

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
   * también la emite el step de imagen. Los acercamientos son exclusivamente informativos.
   */
  private static final Set<Signal> VIDEO_FINDINGS =
      EnumSet.of(
          Signal.PROLONGED_PRESENCE,
          Signal.PROLONGED_VISIT_PRESENCE,
          Signal.LONG_STOP,
          Signal.REPEATED_DIRECTION_CHANGES,
          Signal.RETURNED_TO_PREVIOUS_POSITION,
          Signal.LOITERING);
}
