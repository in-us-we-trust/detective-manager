package com.is2.detective_manager.orchestrator.step.conversationanalysis;

import lombok.AllArgsConstructor;
import lombok.Value;

/** Una evidencia devuelta por el ML de conversación: tipo + score (0..1). */
@Value
@AllArgsConstructor
public class Evidence {
  String type;   // URGENCY, AUTHORITY, PRESSURE, THREAT, ... (dominio del ML)
  double score;  // 0.0 a 1.0
}
