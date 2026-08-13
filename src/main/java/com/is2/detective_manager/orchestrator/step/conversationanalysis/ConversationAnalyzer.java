package com.is2.detective_manager.orchestrator.step.conversationanalysis;

import java.util.List;

/**
 * Colaborador del ConversationAnalysisStep. Llama al ML de conversación y
 * devuelve las evidencias. Es lo único que cambia entre real y mock.
 */
public interface ConversationAnalyzer {

  List<Evidence> analyze(byte[] audio);
}
