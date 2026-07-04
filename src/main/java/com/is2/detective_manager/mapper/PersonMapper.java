package com.is2.detective_manager.mapper;

import com.is2.detective_manager.model.dto.*;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.Person;
import org.mapstruct.*;

import java.util.UUID;

@Mapper(componentModel = "spring")
public interface PersonMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "person", source = "person")
  @Mapping(target = "embedding", source = "generated.embedding")
  @Mapping(target = "modelName", source = "generated.modelName")
  @Mapping(target = "detectorBackend", source = "generated.detectorBackend")
  @Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
  FaceEmbedding toEntity(Person person, GeneratedFaceEmbeddingDto generated, String imageUrl);

  @Mapping(target = "deviceId", source = "person.deviceId")
  @Mapping(target = "personId", source = "person.id")
  @Mapping(target = "personName", source = "person.name")
  @Mapping(target = "dimensions", expression = "java(faceEmbedding.getEmbedding().size())")
  FaceEmbeddingResponseDto toResponseDto(FaceEmbedding faceEmbedding);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "deviceId", source = "deviceId")
  @Mapping(
          target = "normalizedName",
          expression = "java(request.getName().trim().toLowerCase())")
  @Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
  Person toEntity(UUID deviceId, CreatePersonRequestDto request);

  PersonResponseDto toResponseDto(Person person);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "deviceId", ignore = true)
  void update(@MappingTarget Person person, UpdatePersonRequestDto request);

  @Mapping(target = "imageUrl", source = "imageUrl")
  PersonImageResponseDto toPersonImageResponseDto(FaceEmbedding embedding);
}
