package com.is2.detective_manager.orchestrator.evaluator;

/**
 * Vocabulario de señales que los steps pueden emitir. Cada step aporta un set
 * acotado (2-5). El DangerEvaluator reconoce PATRONES de señales presentes.
 */
public enum Signal {

  // --- Step 1: reconocimiento facial ---
  UNKNOWN_PERSON, // la cara no es de las cargadas del timbre
  KNOWN_PERSON,   // la cara es conocida

  // --- Step 2: reconocimiento de objetos ---
  WEAPON_DETECTED,   // se detectó un arma
  COVERED_FACE,      // cara tapada
  NO_THREAT_OBJECT,  // no se detectó ningún objeto amenazante

  // --- Step 3: audio (a futuro) ---
  AGGRESSIVE_TONE, // tono de voz agresivo
  THREAT_KEYWORD,  // palabra clave de amenaza
  CALM_TONE        // tono tranquilo
}
