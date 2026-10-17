package com.is2.detective_manager.orchestrator.step.conversationanalysis;

import com.is2.detective_manager.orchestrator.AnalysisQuality;
import java.util.List;

/** Conserva el texto analizado y distingue evidencias completas de respuestas parciales. */
public record ConversationAnalysisResult(
    String transcript, List<Evidence> evidences, AnalysisQuality quality) {

  public ConversationAnalysisResult(String transcript, List<Evidence> evidences) {
    this(transcript, evidences, AnalysisQuality.VALID);
  }
}
