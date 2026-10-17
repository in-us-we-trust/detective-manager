package com.is2.detective_manager.orchestrator.evaluator;

import java.util.Set;

/** Shared context and wording for a recognized visitor whose request needs attention. */
public final class KnownPersonAttention {

  public static final String URGENCY_MESSAGE =
      "Alguien conocido podría necesitar ayuda. Revisá la situación.";
  public static final String PRESSURE_MESSAGE =
      "Persona reconocida que ejerce presión para que se actúe; conviene revisar la situación";
  public static final String URGENCY_AND_PRESSURE_MESSAGE =
      "Persona reconocida con señales de urgencia que ejerce presión; podría necesitar ayuda";

  private KnownPersonAttention() {}

  public static boolean appliesTo(Set<Signal> signals) {
    return signals.contains(Signal.KNOWN_PERSON)
        && !signals.contains(Signal.UNKNOWN_PERSON)
        && (signals.contains(Signal.URGENCY) || signals.contains(Signal.PRESSURE));
  }
}
