package com.is2.detective_manager.orchestrator.evaluator;

/**
 * Vocabulario de señales que los steps pueden emitir. Cada step aporta un set
 * acotado. El DangerEvaluator reconoce PATRONES de señales presentes.
 * Nota: estas señales son del dominio del orquestador; NO son los tipos del ML
 * (aunque algunos nombres coincidan). Cada step traduce del ML a estas señales.
 */
public enum Signal {

  // --- Reconocimiento facial ---
  UNKNOWN_PERSON, // la cara no es de las cargadas del timbre
  KNOWN_PERSON,   // la cara es conocida

  // --- Reconocimiento de objetos ---
  WEAPON_DETECTED,   // se detectó un arma
  COVERED_FACE,      // cara tapada
  NO_THREAT_OBJECT,  // no se detectó ningún objeto amenazante

  // --- Análisis de conversación (audio) ---
  URGENCY,                // presión de urgencia ("ahora", "ya mismo")
  AUTHORITY,              // se presenta como autoridad para intimidar
  PRESSURE,               // presión psicológica
  VERIFICATION_AVOIDANCE, // evita mostrar documentación / verificarse
  THREAT,                 // amenaza explícita
  CODE_REQUEST,           // pide un código
  MONEY_REQUEST           // pide dinero
}
