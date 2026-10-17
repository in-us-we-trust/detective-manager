package com.is2.detective_manager.orchestrator.evaluator;

import java.util.Set;

/** Describes visit-level recognition results without linking detections to the same person. */
public final class MixedRecognitionEvidence {

  public static final String MESSAGE =
      "Identificación sin confirmar. Revisá quién está en la puerta.";

  static final Set<Signal> SIGNALS = Set.of(Signal.KNOWN_PERSON, Signal.UNKNOWN_PERSON);

  private MixedRecognitionEvidence() {}

  public static boolean appliesTo(Set<Signal> signals) {
    return signals.containsAll(SIGNALS);
  }
}
