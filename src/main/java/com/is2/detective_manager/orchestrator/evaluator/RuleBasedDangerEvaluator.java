package com.is2.detective_manager.orchestrator.evaluator;

import com.is2.detective_manager.orchestrator.Verdict;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Evaluador por reglas de prioridad. Recorre las reglas EN ORDEN (jerarquía) y
 * devuelve el Assessment de la PRIMERA que matchea. Las reglas más específicas /
 * graves van arriba. Sin scoring ni umbrales: es reconocimiento de patrones.
 */
@Slf4j
@Component
public class RuleBasedDangerEvaluator implements DangerEvaluator {

  // Orden = prioridad. La primera que matchea gana. De más grave/específica a más general.
  private static final List<Rule> RULES =
      List.of(
          Rule.builder()
              .requires(EnumSet.of(Signal.WEAPON_DETECTED))
              .severity(Severity.PELIGROSO)
              .verdict(Verdict.DANGER)
              .message("La persona está armada")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.THREAT))
              .severity(Severity.PELIGROSO)
              .verdict(Verdict.DANGER)
              .message("Amenaza verbal detectada")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.LOITERING, Signal.UNKNOWN_PERSON))
              .severity(Severity.SOSPECHOSO)
              .verdict(Verdict.CONTINUE)
              .message("Persona desconocida con posible comportamiento de merodeo")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.FAST_APPROACH, Signal.UNKNOWN_PERSON))
              .severity(Severity.SOSPECHOSO)
              .verdict(Verdict.CONTINUE)
              .message("Persona desconocida acercándose rápidamente")
              .build(),
          Rule.builder()
              .requires(
                  EnumSet.of(
                      Signal.PROLONGED_PRESENCE, Signal.RETURNED_TO_PREVIOUS_POSITION))
              .severity(Severity.SOSPECHOSO)
              .verdict(Verdict.CONTINUE)
              .message("Permanencia prolongada con movimientos de ida y vuelta")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.PROLONGED_PRESENCE, Signal.LONG_STOP))
              .severity(Severity.SOSPECHOSO)
              .verdict(Verdict.CONTINUE)
              .message("Permanencia prolongada con detención extensa")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.LOITERING))
              .severity(Severity.SOSPECHOSO)
              .verdict(Verdict.CONTINUE)
              .message("Posible comportamiento de merodeo")
              .build(),
          // Persona reconocida Y sin objetos amenazantes: se confirma seguro y se concluye.
          Rule.builder()
              .requires(EnumSet.of(Signal.KNOWN_PERSON, Signal.NO_THREAT_OBJECT))
              .severity(Severity.NORMAL)
              .verdict(Verdict.SAFE)
              .message("Persona reconocida")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.COVERED_FACE, Signal.UNKNOWN_PERSON))
              .severity(Severity.SOSPECHOSO)
              .verdict(Verdict.CONTINUE)
              .message("Persona desconocida con la cara tapada")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.AUTHORITY, Signal.URGENCY))
              .severity(Severity.SOSPECHOSO)
              .verdict(Verdict.CONTINUE)
              .message("Posible manipulación: autoridad + urgencia")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.MULTIPLE_PEOPLE))
              .severity(Severity.SOSPECHOSO)
              .verdict(Verdict.CONTINUE)
              .message("Se detectaron varias personas en la puerta")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.APPROACHING_CAMERA, Signal.UNKNOWN_PERSON))
              .severity(Severity.LLAMATIVO)
              .verdict(Verdict.CONTINUE)
              .message("Persona desconocida acercándose")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.PROLONGED_PRESENCE))
              .severity(Severity.LLAMATIVO)
              .verdict(Verdict.CONTINUE)
              .message("Persona presente durante un período prolongado")
              .build(),
          Rule.builder()
              .requires(EnumSet.of(Signal.UNKNOWN_PERSON))
              .severity(Severity.LLAMATIVO)
              .verdict(Verdict.CONTINUE)
              .message("No se reconoce a la persona")
              .build());

  // Si ninguna regla matchea.
  private static final Assessment DEFAULT_ASSESSMENT =
      Assessment.builder()
          .status(AssessmentStatus.EVALUATING)
          .severity(Severity.NORMAL)
          .message("Sin alertas")
          .verdict(Verdict.CONTINUE)
          .build();

  @Override
  public Assessment evaluate(List<Signal> accumulatedSignals) {
    Set<Signal> present =
        accumulatedSignals.isEmpty() ? EnumSet.noneOf(Signal.class) : EnumSet.copyOf(accumulatedSignals);

    for (Rule rule : RULES) {
      if (rule.matches(present)) {
        log.info("Regla matcheó: requires={} -> '{}'", rule.getRequires(), rule.getMessage());
        return Assessment.builder()
            .status(
                rule.getVerdict() == Verdict.CONTINUE
                    ? AssessmentStatus.EVALUATING
                    : AssessmentStatus.CONCLUDED)
            .severity(rule.getSeverity())
            .message(rule.getMessage())
            .verdict(rule.getVerdict())
            .build();
      }
    }

    log.info("Ninguna regla matcheó para señales={} -> default", present);
    return DEFAULT_ASSESSMENT;
  }
}
