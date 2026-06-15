package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.mapper.FaceEmbeddingMapper;
import com.is2.detective_manager.model.dto.CreateFaceEmbeddingRequestDto;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
import com.is2.detective_manager.model.dto.MlFaceEmbeddingRequestDto;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.Person;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
import com.is2.detective_manager.repository.PersonRepository;
import com.is2.detective_manager.service.FaceEmbeddingService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceEmbeddingServiceImpl implements FaceEmbeddingService {

  private final MlServiceApiClient mlServiceApiClient;
  private final FaceEmbeddingRepository faceEmbeddingRepository;
  private final PersonRepository personRepository;
  private final FaceEmbeddingMapper faceEmbeddingMapper;

  @Override
  @Transactional
  public FaceEmbeddingResponseDto createFaceEmbedding(
      UUID deviceId, CreateFaceEmbeddingRequestDto request) {
    log.info(
        "Generating face embedding for device {} and person name {}",
        deviceId,
        request.getPersonName());

    GeneratedFaceEmbeddingDto generated =
        mlServiceApiClient.generateFaceEmbedding(new MlFaceEmbeddingRequestDto(request.getImage()));

    validateGeneratedEmbedding(generated);

    Person person = findOrCreatePerson(deviceId, request.getPersonName());
    FaceEmbedding faceEmbedding = faceEmbeddingMapper.toEntity(person, generated);
    faceEmbedding = faceEmbeddingRepository.save(faceEmbedding);

    log.info(
        "Face embedding saved with ID {} and {} dimensions",
        faceEmbedding.getId(),
        faceEmbedding.getEmbedding().size());

    return faceEmbeddingMapper.toResponseDto(faceEmbedding);
  }

  @Override
  @Transactional(readOnly = true)
  public List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId) {
    log.info("Fetching all face embeddings for device {}", deviceId);

    return faceEmbeddingRepository
        .findAllByPerson_DeviceIdOrderByCreatedAtAscIdAsc(deviceId)
        .stream()
        .map(faceEmbeddingMapper::toResponseDto)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId, Long personId) {
    log.info("Fetching face embeddings for device {} and person {}", deviceId, personId);

    return faceEmbeddingRepository
        .findAllByPerson_DeviceIdAndPerson_IdOrderByCreatedAtAsc(deviceId, personId)
        .stream()
        .map(faceEmbeddingMapper::toResponseDto)
        .toList();
  }

  private Person findOrCreatePerson(UUID deviceId, String personName) {
    String trimmedName = personName.trim();
    String normalizedName = trimmedName.toLowerCase(Locale.ROOT);

    return personRepository
        .findByDeviceIdAndNormalizedName(deviceId, normalizedName)
        .orElseGet(
            () -> {
              Person person = new Person();
              person.setDeviceId(deviceId);
              person.setName(trimmedName);
              person.setNormalizedName(normalizedName);
              return personRepository.save(person);
            });
  }

  private void validateGeneratedEmbedding(GeneratedFaceEmbeddingDto generated) {
    if (generated == null
        || generated.getEmbedding() == null
        || generated.getEmbedding().isEmpty()
        || generated.getModelName() == null
        || generated.getModelName().isBlank()
        || generated.getDetectorBackend() == null
        || generated.getDetectorBackend().isBlank()) {
      throw new DetectiveException(
          ExceptionEnum.ML_SERVICE_ERROR,
          "The ML service returned an invalid face embedding response.");
    }
  }
}
