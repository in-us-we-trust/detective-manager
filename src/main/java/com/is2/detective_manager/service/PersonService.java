package com.is2.detective_manager.service;

import com.is2.detective_manager.model.dto.*;
import java.util.List;
import java.util.UUID;

public interface PersonService {

  List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId);

  List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId, UUID personId);

  PersonResponseDto createPerson(UUID deviceId, CreatePersonRequestDto request);

  List<PersonResponseDto> getPersons(UUID deviceId);

  void deletePerson(UUID deviceId, UUID personId);

  PersonResponseDto getPerson(UUID deviceId, UUID personId);

  PersonResponseDto updatePerson(UUID deviceId, UUID personId, UpdatePersonRequestDto request);

  void deleteImage(UUID deviceId, UUID personId, Long imageId);

  PersonResponseDto addImages(UUID deviceId, UUID personId, AddPersonImagesRequestDto request);
}
