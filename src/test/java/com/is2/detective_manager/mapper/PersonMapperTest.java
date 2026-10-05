package com.is2.detective_manager.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.FaceEmbeddingStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class PersonMapperTest {

  @Test
  void toOrphanEntity_storesEmbeddingWithoutPersonAndWaitingForBatch() {
    PersonMapper mapper = Mappers.getMapper(PersonMapper.class);
    UUID deviceId = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
    GeneratedFaceEmbeddingDto generated =
        new GeneratedFaceEmbeddingDto(List.of(0.1, 0.2), "Facenet512", "opencv");

    FaceEmbedding embedding =
        mapper.toOrphanEntity(deviceId, generated, "https://cdn.example/image.jpg");

    assertNull(embedding.getId());
    assertNull(embedding.getPerson());
    assertEquals(deviceId, embedding.getDeviceId());
    assertEquals(List.of(0.1, 0.2), embedding.getEmbedding());
    assertEquals("Facenet512", embedding.getModelName());
    assertEquals("opencv", embedding.getDetectorBackend());
    assertEquals("https://cdn.example/image.jpg", embedding.getImageUrl());
    assertEquals(FaceEmbeddingStatus.WAITING_BATCH, embedding.getStatus());
    assertNotNull(embedding.getCreatedAt());
  }
}
