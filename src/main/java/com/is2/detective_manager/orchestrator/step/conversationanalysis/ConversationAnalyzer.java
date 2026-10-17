package com.is2.detective_manager.orchestrator.step.conversationanalysis;

/**
 * Colaborador del ConversationAnalysisStep. Llama al ML de conversación y devuelve el texto y las
 * evidencias. Es lo único que cambia entre real y mock.
 */
public interface ConversationAnalyzer {

  ConversationAnalysisResult analyze(byte[] audio);
}
