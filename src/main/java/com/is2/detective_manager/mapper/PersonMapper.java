package com.is2.detective_manager.mapper;

import com.is2.detective_manager.model.dto.*;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.FaceEmbeddingStatus;
import com.is2.detective_manager.model.entity.Person;
import com.is2.detective_manager.model.entity.PersonStatus;
import java.util.UUID;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface PersonMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "person", source = "person")
  @Mapping(target = "deviceId", source = "person.deviceId")
  @Mapping(target = "embedding", source = "generated.embedding")
  @Mapping(target = "modelName", source = "generated.modelName")
  @Mapping(target = "detectorBackend", source = "generated.detectorBackend")
  @Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
  @Mapping(target = "status", expression = "java(com.is2.detective_manager.model.entity.FaceEmbeddingStatus.CREATED_BY_USER)")
  FaceEmbedding toEntity(Person person, GeneratedFaceEmbeddingDto generated, String imageUrl);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "person", ignore = true)
  @Mapping(target = "deviceId", source = "deviceId")
  @Mapping(target = "embedding", source = "generated.embedding")
  @Mapping(target = "modelName", source = "generated.modelName")
  @Mapping(target = "detectorBackend", source = "generated.detectorBackend")
  @Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
  @Mapping(
      target = "status",
      expression = "java(com.is2.detective_manager.model.entity.FaceEmbeddingStatus.WAITING_BATCH)")
  FaceEmbedding toOrphanEntity(UUID deviceId, GeneratedFaceEmbeddingDto generated, String imageUrl);

  @Mapping(target = "deviceId", source = "deviceId")
  @Mapping(target = "personId", source = "person.id")
  @Mapping(target = "personName", source = "person.name")
  @Mapping(target = "dimensions", expression = "java(faceEmbedding.getEmbedding().size())")
  FaceEmbeddingResponseDto toResponseDto(FaceEmbedding faceEmbedding);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "deviceId", source = "deviceId")
  @Mapping(
          target = "normalizedName",
          source = "request.name",
          qualifiedByName = "normalizeName"
  )
  @Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")
  @Mapping(
      target = "status",
      expression = "java(com.is2.detective_manager.model.entity.PersonStatus.KNOWN)")
  Person toEntity(UUID deviceId, CreatePersonRequestDto request);

  PersonResponseDto toResponseDto(Person person);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "deviceId", ignore = true)
  void update(@MappingTarget Person person, UpdatePersonRequestDto request);

  @Mapping(target = "imageUrl", source = "imageUrl")
  PersonImageResponseDto toPersonImageResponseDto(FaceEmbedding embedding);
  @AfterMapping
  default void afterUpdate(@MappingTarget Person person, UpdatePersonRequestDto request) {
    String normalizedName = normalizeName(request.getName());
    if (normalizedName != null) {
      person.setNormalizedName(normalizedName);
    }
  }
  @Named("normalizeName")
  default String normalizeName(String name) {
    if (name == null) {
      return null;
    }

    name = name.trim();
    return name.isEmpty() ? null : name.toLowerCase();
  }

}
