package com.is2.detective_manager.orchestrator.step.videoanalysis;

import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Video step: converts temporal behavioral features into conservative domain signals. */
@Component
@RequiredArgsConstructor
public class VideoAnalysisStep implements AnalysisStep {

  public static final String FEATURES_DATA_KEY = "videoFeatures";

  private final VideoAnalyzer videoAnalyzer;
  private final VideoFeatureCalculator featureCalculator;
  private final VideoAnalysisProperties properties;

  @Override
  public boolean supports(Set<ChunkType> availableInputs) {
    return availableInputs.contains(ChunkType.VIDEO);
  }

  @Override
  public StepResult execute(StepContext context) {
    VideoBehaviorFeatures features =
        featureCalculator.calculate(videoAnalyzer.analyze(context.getPayload()));
    Set<Signal> signals = EnumSet.noneOf(Signal.class);

    for (VideoBehaviorFeatures.PersonFeatures person : features.persons()) {
      if (person.prolongedPresence()) {
        signals.add(Signal.PROLONGED_PRESENCE);
      }
      if (person.longStop()) {
        signals.add(Signal.LONG_STOP);
      }
      if (person.repeatedDirectionChanges()) {
        signals.add(Signal.REPEATED_DIRECTION_CHANGES);
      }
      if (person.returnedToPreviousPosition()) {
        signals.add(Signal.RETURNED_TO_PREVIOUS_POSITION);
      }
      if (person.approachingCamera()) {
        signals.add(Signal.APPROACHING_CAMERA);
      }
      if (person.fastApproach()) {
        signals.add(Signal.FAST_APPROACH);
        signals.add(Signal.APPROACHING_CAMERA);
      }
      if (person.loitering()) {
        signals.add(Signal.LOITERING);
      }
    }

    if (features.longestMultiplePeopleSeconds() >= properties.getMultiplePeopleMinimumSeconds()) {
      signals.add(Signal.MULTIPLE_PEOPLE);
    }

    // Si no se detectó nada extraño en el video, señal de "todo tranquilo".
    if (signals.isEmpty()) {
      signals.add(Signal.NO_SUSPICIOUS_BEHAVIOR);
    }

    StepResult.StepResultBuilder result =
        StepResult.builder()
            .nextStatus(TrackStatus.VIDEO_ANALYZED)
            .data(Map.of(FEATURES_DATA_KEY, features))
            .shouldNotify(false);
    signals.forEach(result::signal);
    return result.build();
  }
}
