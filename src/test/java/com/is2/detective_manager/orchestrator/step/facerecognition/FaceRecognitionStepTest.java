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
  void execute_mixedGroupEmitsBothIdentitiesAndMultiplePeopleWithDerivedDetails() {
    when(faceRecognizer.recognize(IMAGE, DEVICE_ID))
        .thenReturn(
            FaceRecognitionOutcome.fromFaces(
                List.of(face(0, true, "Mateo"), face(1, false, "Mateo"))));

    StepResult result = step.execute(context());

    assertEquals(
        Set.of(Signal.KNOWN_PERSON, Signal.UNKNOWN_PERSON, Signal.MULTIPLE_PEOPLE),
        Set.copyOf(result.getSignals()));
    assertEquals(2, result.getData().get(FaceRecognitionStep.FACE_COUNT_DATA_KEY));
    assertEquals(1L, result.getData().get(FaceRecognitionStep.KNOWN_FACE_COUNT_DATA_KEY));
    assertEquals(1L, result.getData().get(FaceRecognitionStep.UNKNOWN_FACE_COUNT_DATA_KEY));
    assertEquals(List.of("Mateo"), result.getData().get(FaceRecognitionStep.PERSON_NAMES_DATA_KEY));
    assertEquals("SKIPPED_MULTIPLE_FACES", result.getData().get("unknownPersonSuggestion"));
    assertEquals("Rostros reconocidos: 1; rostros no reconocidos: 1", result.getMessage());
  }

  @Test
  void execute_allKnownGroupDoesNotEmitUnknownPerson() {
    when(faceRecognizer.recognize(IMAGE, DEVICE_ID))
        .thenReturn(
            FaceRecognitionOutcome.fromFaces(
                List.of(face(0, true, "Mateo"), face(1, true, "Juli"))));

    StepResult result = step.execute(context());

    assertEquals(
        Set.of(Signal.KNOWN_PERSON, Signal.MULTIPLE_PEOPLE), Set.copyOf(result.getSignals()));
    assertEquals(
        List.of("Mateo", "Juli"), result.getData().get(FaceRecognitionStep.PERSON_NAMES_DATA_KEY));
  }

  @Test
  void execute_allUnknownGroupNeverUsesNearestCandidateNames() {
    when(faceRecognizer.recognize(IMAGE, DEVICE_ID))
        .thenReturn(
            FaceRecognitionOutcome.fromFaces(
                List.of(face(0, false, "Mateo"), face(1, false, "Mateo"))));

    StepResult result = step.execute(context());

    assertEquals(
        Set.of(Signal.UNKNOWN_PERSON, Signal.MULTIPLE_PEOPLE), Set.copyOf(result.getSignals()));
    assertFalse(result.getData().containsKey(FaceRecognitionStep.PERSON_NAME_DATA_KEY));
    assertEquals(List.of(), result.getData().get(FaceRecognitionStep.PERSON_NAMES_DATA_KEY));
  }

  @Test
  void execute_singleNewContractFaceKeepsSinglePersonSignals() {
    when(faceRecognizer.recognize(IMAGE, DEVICE_ID))
        .thenReturn(FaceRecognitionOutcome.fromFaces(List.of(face(0, true, "Mateo"))));

    StepResult result = step.execute(context());

    assertEquals(List.of(Signal.KNOWN_PERSON), result.getSignals());
    assertEquals("Mateo", result.getData().get(FaceRecognitionStep.PERSON_NAME_DATA_KEY));
    assertEquals(null, result.getMessage());
  }

  private FaceRecognitionOutcome.Face face(int index, boolean known, String name) {
    return new FaceRecognitionOutcome.Face(index, null, known, 0.18, 1L, UUID.randomUUID(), name);
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
