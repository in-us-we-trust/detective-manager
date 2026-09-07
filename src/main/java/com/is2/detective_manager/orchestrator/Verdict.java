package com.is2.detective_manager.orchestrator;

/**
 * Estado de peligro del track según las señales acumuladas.
 * Ninguno es terminal: siempre pueden llegar más chunks y cambiar el veredicto.
 */
public enum Verdict {
  CONTINUE, // todavía sin conclusión clara; se sigue evaluando
  DANGER,   // el conjunto de señales indica peligro
  SAFE      // el conjunto de señales indica que es seguro
}
