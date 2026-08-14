package com.is2.detective_manager.orchestrator.evaluator;

/** Nivel de alarma. El 'level' permite comparar cuál es más peligroso. */
public enum Severity {
  NORMAL(0),     // nada llamativo
  LLAMATIVO(1),  // algo que vale la pena avisar, pero no alarmante
  SOSPECHOSO(2), // varias señales, atención
  PELIGROSO(3);  // supera el umbral de peligro -> se corta

  private final int level;

  Severity(int level) {
    this.level = level;
  }

  public int getLevel() {
    return level;
  }
}
