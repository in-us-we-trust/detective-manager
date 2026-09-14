package com.is2.detective_manager.orchestrator.step.conversationanalysis;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Step de audio: analiza la conversación del visitante. Llama al ML (vía ConversationAnalyzer), y
 * traduce sus evidencias a Signals del dominio del orquestador: si el score de una evidencia supera
 * su umbral, se emite la Signal mapeada.
 */
@Component
@RequiredArgsConstructor
public class ConversationAnalysisStep implements AnalysisStep {

  private final ConversationAnalyzer conversationAnalyzer;

  /** Umbral por tipo de evidencia del ML. Por ahora: todo > 0.8 dispara señal. */
  private static final double DEFAULT_THRESHOLD = 0.8;

  private static final Map<String, Double> THRESHOLDS =
      Map.of(
          "URGENCY", DEFAULT_THRESHOLD,
          "AUTHORITY", DEFAULT_THRESHOLD,
          "PRESSURE", DEFAULT_THRESHOLD,
          "VERIFICATION_AVOIDANCE", DEFAULT_THRESHOLD,
          "THREAT", DEFAULT_THRESHOLD,
          "CODE_REQUEST", DEFAULT_THRESHOLD,
          "MONEY_REQUEST", DEFAULT_THRESHOLD);

  /** Mapeo del tipo del ML (su dominio) a la Signal del orquestador (nuestro dominio). */
  private static final Map<String, Signal> TYPE_TO_SIGNAL =
      Map.of(
          "URGENCY", Signal.URGENCY,
          "AUTHORITY", Signal.AUTHORITY,
          "PRESSURE", Signal.PRESSURE,
          "VERIFICATION_AVOIDANCE", Signal.VERIFICATION_AVOIDANCE,
          "THREAT", Signal.THREAT,
          "CODE_REQUEST", Signal.CODE_REQUEST,
          "MONEY_REQUEST", Signal.MONEY_REQUEST);

  @Override
  public boolean supports(Set<ChunkType> availableInputs) {
    return availableInputs.contains(ChunkType.AUDIO);
  }

  @Override
  public StepResult execute(StepContext context) {
    List<Evidence> evidences = conversationAnalyzer.analyze(context.getPayload());

    StepResult.StepResultBuilder result =
        StepResult.builder().nextStatus(TrackStatus.AUDIO_TAGGED).shouldNotify(false);

    // Cada evidencia que supera su umbral se traduce a su Signal.
    for (Evidence ev : evidences) {
      double threshold = THRESHOLDS.getOrDefault(ev.getType(), DEFAULT_THRESHOLD);
      Signal signal = TYPE_TO_SIGNAL.get(ev.getType());
      if (signal != null && ev.getScore() > threshold) {
        result.signal(signal);
      }
    }

    return result.build();
  }
}
