package com.is2.detective_manager.orchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalysisStep;
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionStep;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectRecognitionStep;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisStep;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisPipelineVideoTest {

  @Test
  void stepsForVideo_selectsOnlyVideoAnalysis() {
    FaceRecognitionStep face = mock(FaceRecognitionStep.class);
    ObjectRecognitionStep objects = mock(ObjectRecognitionStep.class);
    ConversationAnalysisStep conversation = mock(ConversationAnalysisStep.class);
    VideoAnalysisStep video = mock(VideoAnalysisStep.class);
    org.mockito.Mockito.when(video.supports(java.util.Set.of(ChunkType.VIDEO))).thenReturn(true);

    AnalysisPipeline pipeline = new AnalysisPipeline(face, objects, conversation, video);

    assertEquals(List.of(video), pipeline.stepsFor(ChunkType.VIDEO));
  }
}
