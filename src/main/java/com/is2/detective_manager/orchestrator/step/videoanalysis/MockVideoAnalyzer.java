package com.is2.detective_manager.orchestrator.step.videoanalysis;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Predictable video analyzer used with the mock-video profile. */
@Component
@Profile("mock-video")
public class MockVideoAnalyzer implements VideoAnalyzer {

  @Override
  public VideoAnalysisResult analyze(byte[] video) {
    VideoAnalysisResult.VideoMetadata metadata =
        new VideoAnalysisResult.VideoMetadata(1920, 1080, 1, 25, 25);
    List<VideoAnalysisResult.VideoObservation> observations =
        java.util.stream.IntStream.rangeClosed(0, 24)
            .mapToObj(
                second ->
                    new VideoAnalysisResult.VideoObservation(
                        second, second, 1, 0.95, 40, 20, 60, 90, 50, 55, 50, 55, 50, 90, 14, 0.1))
            .toList();
    return new VideoAnalysisResult(metadata, observations);
  }
}
