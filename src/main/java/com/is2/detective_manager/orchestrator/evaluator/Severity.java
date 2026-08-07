package com.is2.detective_manager.orchestrator.evaluator;

/** Nivel de alarma según el score acumulado. */
public enum Severity {
  NORMAL,     // nada llamativo
  LLAMATIVO,  // algo que vale la pena avisar, pero no alarmante
  SOSPECHOSO, // varias señales, atención
  PELIGROSO   // supera el umbral de peligro -> se corta
}
