package com.is2.detective_manager.orchestrator.step.videoanalysis;

import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
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
    VideoAnalysisResult analysis = videoAnalyzer.analyze(context.getPayload());
    if (analysis == null || analysis.metadata() == null || analysis.observations() == null) {
      throw new DetectiveException(ExceptionEnum.ML_SERVICE_ERROR);
    }
    VideoBehaviorFeatures features = featureCalculator.calculate(analysis);
    StepResult.StepResultBuilder result =
        StepResult.builder()
            .nextStatus(TrackStatus.VIDEO_ANALYZED)
            .data(Map.of(FEATURES_DATA_KEY, features))
            .shouldNotify(false);
    if (features.persons().isEmpty()) {
      if (analysis.receivedObservationCount() == 0) {
        return result.message("No se detectaron personas en el video").build();
      }
      return result
          .quality(AnalysisQuality.INSUFFICIENT)
          .message("No hay observaciones utilizables para evaluar el comportamiento en el video")
          .build();
    }
    boolean incompleteObservations =
        analysis.receivedObservationCount() > analysis.observations().size();
    if (incompleteObservations) {
      result
          .quality(AnalysisQuality.INSUFFICIENT)
          .message(
              "Algunas observaciones del video no pudieron utilizarse para evaluar el comportamiento");
    }
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

    // Las observaciones utilizables no alcanzaron los umbrales de comportamiento.
    if (signals.isEmpty() && !incompleteObservations) {
      signals.add(Signal.NO_SUSPICIOUS_BEHAVIOR);
    }

    signals.forEach(result::signal);
    return result.build();
  }
}
