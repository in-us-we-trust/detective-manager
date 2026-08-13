package com.is2.detective_manager.orchestrator.step.conversationanalysis;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock: devuelve evidencias fijas (las del ejemplo real del ML). */
@Slf4j
@Component
@Profile("mock-conversation")
public class MockConversationAnalyzer implements ConversationAnalyzer {

  @Override
  public List<Evidence> analyze(byte[] audio) {
    log.info("[mock] Análisis de conversación simulado");
    return List.of(
        new Evidence("URGENCY", 0.9296),
        new Evidence("AUTHORITY", 0.8557),
        new Evidence("PRESSURE", 0.6417),
        new Evidence("VERIFICATION_AVOIDANCE", 0.2818),
        new Evidence("THREAT", 0.1914),
        new Evidence("CODE_REQUEST", 0.0056),
        new Evidence("MONEY_REQUEST", 0.0001));
  }
}
