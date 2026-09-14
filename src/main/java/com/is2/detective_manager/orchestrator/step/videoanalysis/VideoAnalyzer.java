package com.is2.detective_manager.orchestrator.step.videoanalysis;

/** Sends a video to the configured analyzer and returns objective tracking observations. */
public interface VideoAnalyzer {

  VideoAnalysisResult analyze(byte[] video);
}
