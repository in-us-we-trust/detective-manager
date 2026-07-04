package com.is2.detective_manager.service;

import com.is2.detective_manager.model.dto.*;
import java.util.List;
import java.util.UUID;

public interface PersonService {

  List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId);

  List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId, Long personId);

  PersonResponseDto createPerson(UUID deviceId, CreatePersonRequestDto request);

  List<PersonResponseDto> getPersons(UUID deviceId);

  void deletePerson(UUID deviceId, Long personId);

  PersonResponseDto getPerson(UUID deviceId, Long personId);

  PersonResponseDto updatePerson(UUID deviceId, Long personId, UpdatePersonRequestDto request);

  void deleteImage(UUID deviceId, Long personId, Long imageId);

  PersonResponseDto addImages(UUID deviceId, Long personId, AddPersonImagesRequestDto request);
}
