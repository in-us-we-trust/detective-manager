package com.is2.detective_manager.orchestrator.step.facerecognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FaceRecognitionStepTest {

  private static final UUID DEVICE_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
  private static final byte[] IMAGE = "image".getBytes();

  @Mock private FaceRecognizer faceRecognizer;
  @InjectMocks private FaceRecognitionStep step;

  @Test
  void execute_knownPersonExposesNameWithoutExtraSignals() {
    when(faceRecognizer.recognize(IMAGE, DEVICE_ID))
        .thenReturn(FaceRecognitionOutcome.known("Juan Pérez"));

    StepResult result = step.execute(context());

    assertEquals(List.of(Signal.KNOWN_PERSON), result.getSignals());
    assertEquals(true, result.getData().get("faceKnown"));
    assertEquals("Juan Pérez", result.getData().get(FaceRecognitionStep.PERSON_NAME_DATA_KEY));
    verify(faceRecognizer).recognize(IMAGE, DEVICE_ID);
  }

  @Test
  void execute_unknownPersonDoesNotExposeAName() {
    when(faceRecognizer.recognize(IMAGE, DEVICE_ID)).thenReturn(FaceRecognitionOutcome.unknown());

    StepResult result = step.execute(context());

    assertTrue(result.getSignals().contains(Signal.UNKNOWN_PERSON));
    assertFalse(result.getSignals().contains(Signal.KNOWN_PERSON));
    assertEquals(false, result.getData().get("faceKnown"));
    assertFalse(result.getData().containsKey(FaceRecognitionStep.PERSON_NAME_DATA_KEY));
  }

  @Test
  void supportsOnlyImage() {
    assertTrue(step.supports(Set.of(ChunkType.IMAGE)));
    assertFalse(step.supports(Set.of(ChunkType.VIDEO)));
    assertFalse(step.supports(Set.of(ChunkType.AUDIO)));
  }

  private static StepContext context() {
    return StepContext.builder()
        .trackId("track")
        .deviceId(DEVICE_ID)
        .mediaType(ChunkType.IMAGE)
        .payload(IMAGE)
        .accumulated(Map.of())
        .build();
  }
}
