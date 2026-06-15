package com.is2.detective_manager.mapper;

import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.Person;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FaceEmbeddingMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "person", source = "person")
  @Mapping(target = "embedding", source = "generated.embedding")
  @Mapping(target = "modelName", source = "generated.modelName")
  @Mapping(target = "detectorBackend", source = "generated.detectorBackend")
  @Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
  FaceEmbedding toEntity(Person person, GeneratedFaceEmbeddingDto generated);

  @Mapping(target = "deviceId", source = "person.deviceId")
  @Mapping(target = "personId", source = "person.id")
  @Mapping(target = "personName", source = "person.name")
  @Mapping(target = "dimensions", expression = "java(faceEmbedding.getEmbedding().size())")
  FaceEmbeddingResponseDto toResponseDto(FaceEmbedding faceEmbedding);
}
