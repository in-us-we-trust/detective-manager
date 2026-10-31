package com.is2.detective_manager.orchestrator.step.facerecognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.FaceRecognitionClosestMatchDto;
import com.is2.detective_manager.model.dto.FaceRecognitionFaceDto;
import com.is2.detective_manager.model.dto.FaceRecognitionFaceDto.FaceBox;
import com.is2.detective_manager.model.dto.FaceRecognitionResultDto;
import com.is2.detective_manager.service.PersonService;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RealFaceRecognizerTest {

  private static final UUID DEVICE_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
  private static final byte[] IMAGE = "image".getBytes();

  @Mock private MlServiceApiClient mlServiceApiClient;
  @Mock private PersonService personService;
  @InjectMocks private RealFaceRecognizer recognizer;

  @Test
  void recognize_knownPersonReturnsNameFromTheSameMlResponse() {
    FaceEmbeddingResponseDto dto =
        FaceEmbeddingResponseDto.builder().personName("Juan Pérez").build();
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of(dto));
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(
            FaceRecognitionResultDto.builder()
                .known(true)
                .closestMatch(
                    FaceRecognitionClosestMatchDto.builder().personName("Juan Pérez").build())
                .build());

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertTrue(outcome.known());
    assertEquals("Juan Pérez", outcome.personName());
    verify(mlServiceApiClient, never()).generateFaceEmbedding(any());

    ArgumentCaptor<com.is2.detective_manager.model.dto.MlFaceRecognitionRequestDto> request =
        ArgumentCaptor.forClass(
            com.is2.detective_manager.model.dto.MlFaceRecognitionRequestDto.class);
    verify(mlServiceApiClient).recognizeFace(request.capture());
    assertEquals(Base64.getEncoder().encodeToString(IMAGE), request.getValue().getImage());
  }

  @Test
  void recognize_unknownPersonHasNoName() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(
            FaceRecognitionResultDto.builder()
                .known(false)
                .closestMatch(
                    FaceRecognitionClosestMatchDto.builder()
                        .personName("Nearest candidate")
                        .build())
                .build());

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertFalse(outcome.known());
    assertNull(outcome.personName());
  }

  @Test
  void recognize_mixedFacesKeepUnknownCompanionsWithoutRecognizingTheirNearestCandidate() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(
            FaceRecognitionResultDto.builder()
                .known(true)
                .closestMatch(
                    FaceRecognitionClosestMatchDto.builder()
                        .personName("Global candidate is ignored")
                        .build())
                .faces(List.of(face(0, true, 0.18, "Mateo"), face(1, false, 0.61, "Mateo")))
                .build());

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertTrue(outcome.known());
    assertTrue(outcome.hasUnknownFaces());
    assertEquals("Mateo", outcome.personName());
    assertEquals(List.of("Mateo"), outcome.personNames());
    assertEquals(2, outcome.faces().size());
    FaceRecognitionOutcome.Face unknown = outcome.faces().get(1);
    assertFalse(unknown.known());
    assertNull(unknown.personName());
    assertNull(unknown.personId());
    assertNull(unknown.embeddingId());
    assertEquals(0.61, unknown.distance());
    assertEquals(new FaceBox(350, 85, 85, 105), unknown.box());
    verify(mlServiceApiClient, never()).generateFaceEmbedding(any());
  }

  @Test
  void recognize_allKnownFacesUseBestKnownMatchAndPreserveDistinctNames() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(
            FaceRecognitionResultDto.builder()
                .known(true)
                .faces(List.of(face(0, true, 0.25, " Juli "), face(1, true, 0.18, "Mateo")))
                .build());

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertTrue(outcome.known());
    assertFalse(outcome.hasUnknownFaces());
    assertEquals("Mateo", outcome.personName());
    assertEquals(List.of("Juli", "Mateo"), outcome.personNames());
  }

  @Test
  void recognize_facesWithoutCompatibleCandidatesAcceptNullDistanceAndMatch() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(
            FaceRecognitionResultDto.builder()
                .known(false)
                .faces(List.of(FaceRecognitionFaceDto.builder().faceIndex(0).known(false).build()))
                .build());

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertFalse(outcome.known());
    assertTrue(outcome.hasUnknownFaces());
    assertNull(outcome.personName());
    assertNull(outcome.faces().get(0).distance());
    assertNull(outcome.faces().get(0).personId());
    assertTrue(outcome.personNames().isEmpty());
  }

  @Test
  void recognize_singleFaceUsesItsRecognitionAndName() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(
            FaceRecognitionResultDto.builder()
                .known(true)
                .faces(List.of(face(0, true, 0.18, "Mateo")))
                .build());

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertTrue(outcome.known());
    assertEquals("Mateo", outcome.personName());
    assertFalse(outcome.hasUnknownFaces());
    assertEquals(List.of("Mateo"), outcome.personNames());
  }

  @Test
  void recognize_incompleteOrInconsistentFaceListsFailInsteadOfUsingGlobalKnown() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    List<List<FaceRecognitionFaceDto>> invalid =
        List.of(
            List.of(),
            Arrays.asList(face(0, true, 0.18, "Mateo"), null),
            List.of(FaceRecognitionFaceDto.builder().faceIndex(0).build()),
            List.of(FaceRecognitionFaceDto.builder().known(true).build()),
            List.of(face(-1, true, 0.18, "Mateo")),
            List.of(face(0, true, 0.18, "Mateo"), face(0, false, 0.6, "Mateo")),
            List.of(face(0, false, 0.6, "Mateo")),
            List.of(face(0, true, Double.NaN, "Mateo")));
    for (List<FaceRecognitionFaceDto> faces : invalid) {
      when(mlServiceApiClient.recognizeFace(any()))
          .thenReturn(FaceRecognitionResultDto.builder().known(true).faces(faces).build());
      assertThrows(DetectiveException.class, () -> recognizer.recognize(IMAGE, DEVICE_ID));
    }
  }

  private FaceRecognitionFaceDto face(int index, boolean known, Double distance, String name) {
    return FaceRecognitionFaceDto.builder()
        .faceIndex(index)
        .known(known)
        .distance(distance)
        .box(new FaceBox(350, 85, 85, 105))
        .closestMatch(
            FaceRecognitionClosestMatchDto.builder()
                .embeddingId(1L)
                .personId(UUID.fromString("22222222-2222-4222-8222-222222222222"))
                .personName(name)
                .build())
        .build();
  }

  @Test
  void recognize_knownPersonWithoutClosestMatchKeepsRecognitionWithoutAName() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(FaceRecognitionResultDto.builder().known(true).build());

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertTrue(outcome.known());
    assertNull(outcome.personName());
  }

  @Test
  void recognize_knownPersonNameHasSurroundingWhitespaceRemoved() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(
            FaceRecognitionResultDto.builder()
                .known(true)
                .closestMatch(
                    FaceRecognitionClosestMatchDto.builder().personName(" \tJuan Pérez\n").build())
                .build());

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertTrue(outcome.known());
    assertEquals("Juan Pérez", outcome.personName());
  }

  @Test
  void recognize_nullMlResultFailsInsteadOfReportingUnknownPerson() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any())).thenReturn(null);

    assertThrows(DetectiveException.class, () -> recognizer.recognize(IMAGE, DEVICE_ID));
  }

  @Test
  void recognize_missingKnownValueFailsInsteadOfReportingUnknownPerson() {
    when(personService.getFaceEmbeddings(DEVICE_ID)).thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(FaceRecognitionResultDto.builder().build());

    assertThrows(DetectiveException.class, () -> recognizer.recognize(IMAGE, DEVICE_ID));
  }
}
