package com.is2.detective_manager.orchestrator.step.facerecognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.mapper.PersonMapper;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.FaceRecognitionClosestMatchDto;
import com.is2.detective_manager.model.dto.FaceRecognitionResultDto;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.PersonStatus;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
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
  @Mock private FaceEmbeddingRepository faceEmbeddingRepository;
  @Mock private PersonMapper personMapper;
  @InjectMocks private RealFaceRecognizer recognizer;

  @Test
  void recognize_knownPersonReturnsNameFromTheSameMlResponse() {
    FaceEmbedding embedding = new FaceEmbedding();
    FaceEmbeddingResponseDto dto =
        FaceEmbeddingResponseDto.builder().personName("Juan Pérez").build();
    when(faceEmbeddingRepository.findAllByPerson_DeviceIdAndPerson_StatusOrderByCreatedAtAscIdAsc(
            DEVICE_ID, PersonStatus.KNOWN))
        .thenReturn(List.of(embedding));
    when(personMapper.toResponseDto(embedding)).thenReturn(dto);
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
    when(faceEmbeddingRepository.findAllByPerson_DeviceIdAndPerson_StatusOrderByCreatedAtAscIdAsc(
            DEVICE_ID, PersonStatus.KNOWN))
        .thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(FaceRecognitionResultDto.builder().known(false).build());

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertFalse(outcome.known());
    assertNull(outcome.personName());
  }

  @Test
  void recognize_nullMlResultIsUnknown() {
    when(faceEmbeddingRepository.findAllByPerson_DeviceIdAndPerson_StatusOrderByCreatedAtAscIdAsc(
            DEVICE_ID, PersonStatus.KNOWN))
        .thenReturn(List.of());
    when(mlServiceApiClient.recognizeFace(any())).thenReturn(null);

    FaceRecognitionOutcome outcome = recognizer.recognize(IMAGE, DEVICE_ID);

    assertFalse(outcome.known());
    assertNull(outcome.personName());
  }
}
