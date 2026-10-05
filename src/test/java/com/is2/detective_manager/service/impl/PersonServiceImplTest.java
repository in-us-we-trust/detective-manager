package com.is2.detective_manager.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.mapper.PersonMapper;
import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
import com.is2.detective_manager.model.dto.MlFaceEmbeddingRequestDto;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.FaceEmbeddingStatus;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
import com.is2.detective_manager.repository.PersonRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonServiceImplTest {

  private static final UUID DEVICE_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");

  @Mock private MlServiceApiClient mlServiceApiClient;
  @Mock private FaceEmbeddingRepository faceEmbeddingRepository;
  @Mock private PersonRepository personRepository;
  @Mock private PersonMapper personMapper;
  @Mock private CloudinaryStorageClient cloudinaryStorageClient;

  @InjectMocks private PersonServiceImpl service;

  @Test
  void saveOrphanEmbeddingIfPossible_persistsWaitingBatchEmbeddingWithoutPerson() {
    GeneratedFaceEmbeddingDto generated =
        new GeneratedFaceEmbeddingDto(List.of(0.1, 0.2), "Facenet512", "opencv");
    FaceEmbedding embedding = new FaceEmbedding();
    embedding.setStatus(FaceEmbeddingStatus.WAITING_BATCH);
    when(mlServiceApiClient.generateFaceEmbedding(any())).thenReturn(generated);
    when(personMapper.toOrphanEntity(DEVICE_ID, generated, "https://cdn.example/image.jpg"))
        .thenReturn(embedding);

    service.saveOrphanEmbeddingIfPossible(DEVICE_ID, "aW1hZ2U=", "https://cdn.example/image.jpg");

    verify(mlServiceApiClient).generateFaceEmbedding(new MlFaceEmbeddingRequestDto("aW1hZ2U="));
    verify(faceEmbeddingRepository).save(embedding);
  }

  @Test
  void saveOrphanEmbeddingIfPossible_skipsInvalidEmbedding() {
    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenReturn(new GeneratedFaceEmbeddingDto(List.of(), "Facenet512", "opencv"));

    service.saveOrphanEmbeddingIfPossible(DEVICE_ID, "aW1hZ2U=", "https://cdn.example/image.jpg");

    verify(personMapper, never()).toOrphanEntity(any(), any(), any());
    verify(faceEmbeddingRepository, never()).save(any());
  }

  @Test
  void saveOrphanEmbeddingIfPossible_continuesWhenMlFails() {
    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenThrow(new RuntimeException("ml down"));

    assertDoesNotThrow(
        () ->
            service.saveOrphanEmbeddingIfPossible(
                DEVICE_ID, "aW1hZ2U=", "https://cdn.example/image.jpg"));
    verify(faceEmbeddingRepository, never()).save(any());
  }
}
