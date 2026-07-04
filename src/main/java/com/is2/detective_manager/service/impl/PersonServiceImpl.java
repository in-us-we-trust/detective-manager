package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.mapper.PersonMapper;
import com.is2.detective_manager.model.dto.*;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.Person;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
import com.is2.detective_manager.repository.PersonRepository;
import com.is2.detective_manager.service.PersonService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PersonServiceImpl implements PersonService {

  private final MlServiceApiClient mlServiceApiClient;
  private final FaceEmbeddingRepository faceEmbeddingRepository;
  private final PersonRepository personRepository;
  private final PersonMapper personMapper;
  private final CloudinaryStorageClient cloudinaryStorageClient;

  @Override
  @Transactional(readOnly = true)
  public List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId) {
    log.info("Fetching all face embeddings for device {}", deviceId);

    return faceEmbeddingRepository
        .findAllByPerson_DeviceIdOrderByCreatedAtAscIdAsc(deviceId)
        .stream()
        .map(personMapper::toResponseDto)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<FaceEmbeddingResponseDto> getFaceEmbeddings(UUID deviceId, Long personId) {
    log.info("Fetching face embeddings for device {} and person {}", deviceId, personId);

    return faceEmbeddingRepository
        .findAllByPerson_DeviceIdAndPerson_IdOrderByCreatedAtAsc(deviceId, personId)
        .stream()
        .map(personMapper::toResponseDto)
        .toList();
  }

  @Override
  @Transactional
  public PersonResponseDto createPerson(UUID deviceId, CreatePersonRequestDto request) {
    log.info(
            "Creating person {} with {} images for device {}",
            request.getName(),
            request.getImages().size(),
            deviceId);

    Person person = personMapper.toEntity(deviceId, request);
    person = personRepository.save(person);

    for (String image : request.getImages()) {
      String imageUrl = cloudinaryStorageClient.uploadEventImage(image, deviceId);

      createEmbedding(person, image, imageUrl);
    }

    log.info(
            "Person {} created with {} face embeddings",
            person.getId(),
            request.getImages().size());

    return toResponseWithImages(deviceId, person);
  }
  private FaceEmbedding createEmbedding(Person person, String image, String imageUrl) {

    GeneratedFaceEmbeddingDto generated =
            mlServiceApiClient.generateFaceEmbedding(
                    new MlFaceEmbeddingRequestDto(image));

    validateGeneratedEmbedding(generated);

    FaceEmbedding embedding =
            personMapper.toEntity(person, generated, imageUrl);

    return faceEmbeddingRepository.save(embedding);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PersonResponseDto> getPersons(UUID deviceId) {
    log.info("Fetching persons for device {}", deviceId);

    return personRepository.findAllByDeviceIdOrderByCreatedAtAscIdAsc(deviceId).stream()
            .map(person -> toResponseWithImages(deviceId, person))
            .toList();
  }

  @Override
  @Transactional
  public void deletePerson(UUID deviceId, Long personId) {
    log.info("Deleting person {} for device {}", personId, deviceId);
    Person person = getPersonEntity(deviceId, personId);

    faceEmbeddingRepository.deleteAllByPerson(person);

    personRepository.delete(person);
  }

  @Override
  public PersonResponseDto getPerson(UUID deviceId, Long personId) {
    log.info("Fetching person {} for device {}", personId, deviceId);
    Person person = getPersonEntity(deviceId, personId);
    return toResponseWithImages(deviceId, person);
  }
  private Person getPersonEntity(UUID deviceId, Long personId) {
    return personRepository
            .findByDeviceIdAndId(deviceId, personId)
            .orElseThrow(
                    () ->
                            new DetectiveException(
                                    ExceptionEnum.PERSON_NOT_FOUND,
                                    "Person not found."));
  }

  @Override
  @Transactional
  public PersonResponseDto updatePerson(
          UUID deviceId,
          Long personId,
          UpdatePersonRequestDto request) {

    log.info("Updating person {} for device {}", personId, deviceId);

    Person person = getPersonEntity(deviceId, personId);

    personMapper.update(person, request);

    person = personRepository.save(person);

    return toResponseWithImages(deviceId, person);
  }


  @Override
  @Transactional
  public void deleteImage(UUID deviceId, Long personId, Long imageId) {

    FaceEmbedding embedding =
            faceEmbeddingRepository
                    .findById(imageId)
                    .orElseThrow(() -> new DetectiveException(ExceptionEnum.IMAGE_NOT_FOUND, "Image not found"));

    if (!embedding.getPerson().getId().equals(personId)
            || !embedding.getPerson().getDeviceId().equals(deviceId)) {
      throw new DetectiveException(ExceptionEnum.NOT_ALLOWED_IMAGE_ACTION, "Invalid access");
    }

    faceEmbeddingRepository.delete(embedding);
  }

  @Override
  public PersonResponseDto addImages(UUID deviceId, Long personId, AddPersonImagesRequestDto request) {
    log.info("Adding {} images to person {} for device {}", request.getImages().size(), personId, deviceId);

    Person person = getPersonEntity(deviceId, personId);

    for (String image : request.getImages()) {
      String imageUrl = cloudinaryStorageClient.uploadEventImage(image, deviceId);
      createEmbedding(person, image, imageUrl);
    }

    return toResponseWithImages(deviceId, person);
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

  private PersonResponseDto toResponseWithImages(UUID deviceId, Person person) {
    var response = personMapper.toResponseDto(person);
    var list = faceEmbeddingRepository.findAllByPerson_DeviceIdAndPerson_IdOrderByCreatedAtAsc(
                              deviceId, person.getId())
                      .stream()
                      .map(personMapper::toPersonImageResponseDto)
                      .toList();
    response.setImages(list);
    return response;
  }
}
