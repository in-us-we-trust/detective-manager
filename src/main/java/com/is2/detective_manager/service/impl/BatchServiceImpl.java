package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.client.UnknownPersonEventPublisher;
import com.is2.detective_manager.mapper.PersonMapper;
import com.is2.detective_manager.model.dto.BatchResponseDto;
import com.is2.detective_manager.model.dto.ClusterDto;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.MlClusteringRequestDto;
import com.is2.detective_manager.model.dto.UnknownPersonDetectedEventDto;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.FaceEmbeddingStatus;
import com.is2.detective_manager.model.entity.Person;
import com.is2.detective_manager.model.entity.PersonStatus;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
import com.is2.detective_manager.repository.PersonRepository;
import com.is2.detective_manager.service.BatchService;
import com.is2.detective_manager.service.PersonService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BatchServiceImpl implements BatchService {

  private final FaceEmbeddingRepository faceEmbeddingRepository;
  private final PersonRepository personRepository;
  private final MlServiceApiClient mlServiceApiClient;
  private final UnknownPersonEventPublisher unknownPersonEventPublisher;
  private final PersonMapper personMapper;
  private final PersonService personService;

  @Value("${batch.clustering.min-distinct-days:3}")
  private int minDistinctDays;

  @Value("${batch.clustering.orphan-expiry-days:30}")
  private int orphanExpiryDays;

  @Value("${batch.clustering.ignored-person-expiry-days:1}")
  private int ignoredPersonExpiryDays;

  @Override
  @Transactional
  public BatchResponseDto runUnknownPersonsBatch(int deleted) {
    List<FaceEmbedding> orphans = faceEmbeddingRepository.findAllByPersonIsNull();
    log.info("Found {} orphan embeddings to process", orphans.size());

    if (orphans.isEmpty()) {
      return BatchResponseDto.builder()
          .deletedOrphanEmbeddings(deleted)
          .orphanEmbeddingsProcessed(0)
          .unknownPersonsCreated(0)
          .build();
    }

    List<FaceEmbeddingResponseDto> embeddingDtos =
        orphans.stream().map(personMapper::toResponseDto).toList();

    List<ClusterDto> clusters =
        mlServiceApiClient.clusterEmbeddings(new MlClusteringRequestDto(embeddingDtos));
    log.info("ML service returned {} clusters", clusters.size());

    Map<Long, FaceEmbedding> embeddingById =
        orphans.stream().collect(Collectors.toMap(FaceEmbedding::getId, e -> e));

    int personsCreated = 0;
    for (ClusterDto cluster : clusters) {
      personsCreated += processCluster(cluster, embeddingById);
    }

    log.info(
        "Batch completed: deleted={}, processed={}, created={}",
        deleted,
        orphans.size(),
        personsCreated);

    return BatchResponseDto.builder()
        .deletedOrphanEmbeddings(deleted)
        .orphanEmbeddingsProcessed(orphans.size())
        .unknownPersonsCreated(personsCreated)
        .build();
  }

  public int deleteNonKnownPersonsCreatedBefore() {
    LocalDateTime threshold = LocalDateTime.now().minusDays(ignoredPersonExpiryDays);
    List<Person> persons =
        personRepository.findAllByStatusNotAndCreatedAtBefore(PersonStatus.KNOWN, threshold);
    persons.forEach(person -> personService.deletePerson(person.getDeviceId(), person.getId()));

    return persons.size();
  }

  private int deleteExpiredOrphanEmbeddings() {
    LocalDateTime threshold = LocalDateTime.now().minusDays(orphanExpiryDays);
    return faceEmbeddingRepository.deleteOrphanEmbeddingsOlderThan(threshold);
  }

  @Override
  @Transactional
  public int deleteOldPersonInformation() {
    var embeddingsDeleted = deleteExpiredOrphanEmbeddings();
    log.info("Deleted {} expired orphan embeddings", embeddingsDeleted);
    var personsDeleted = deleteNonKnownPersonsCreatedBefore();
    log.info("Deleted {} non-known persons created before threshold", personsDeleted);

    return personsDeleted + embeddingsDeleted;
  }

  private int processCluster(ClusterDto cluster, Map<Long, FaceEmbedding> embeddingById) {
    if (cluster.getEmbeddingIds() == null || cluster.getEmbeddingIds().isEmpty()) {
      return 0;
    }

    List<FaceEmbedding> clusterEmbeddings =
        cluster.getEmbeddingIds().stream().map(embeddingById::get).filter(e -> e != null).toList();

    if (clusterEmbeddings.isEmpty()) {
      return 0;
    }

    Map<UUID, List<FaceEmbedding>> byDevice =
        clusterEmbeddings.stream().collect(Collectors.groupingBy(FaceEmbedding::getDeviceId));

    int created = 0;
    for (Map.Entry<UUID, List<FaceEmbedding>> entry : byDevice.entrySet()) {
      UUID deviceId = entry.getKey();
      List<FaceEmbedding> deviceEmbeddings = entry.getValue();

      Set<LocalDate> distinctDays =
          deviceEmbeddings.stream()
              .map(e -> e.getCreatedAt().toLocalDate())
              .collect(Collectors.toSet());

      log.info(
          "Cluster for device {}: {} embeddings across {} distinct days",
          deviceId,
          deviceEmbeddings.size(),
          distinctDays.size());

      if (distinctDays.size() < minDistinctDays) {
        log.info(
            "Cluster for device {} has only {} distinct days (min {}), ignoring",
            deviceId,
            distinctDays.size(),
            minDistinctDays);
        markEmbeddingsIgnored(deviceEmbeddings);
        continue;
      }

      Person unknownPerson = createUnknownPerson(deviceId);
      associateEmbeddings(deviceEmbeddings, unknownPerson);
      publishUnknownPersonEvent(unknownPerson, deviceEmbeddings);
      created++;
    }

    return created;
  }

  private Person createUnknownPerson(UUID deviceId) {
    Person person = new Person();
    person.setDeviceId(deviceId);
    person.setStatus(PersonStatus.UNKNOWN);
    person.setCreatedAt(LocalDateTime.now());
    person = personRepository.save(person);
    log.info("Created UNKNOWN person {} for device {}", person.getId(), deviceId);
    return person;
  }

  private void associateEmbeddings(List<FaceEmbedding> embeddings, Person person) {
    for (FaceEmbedding embedding : embeddings) {
      embedding.setPerson(person);
      embedding.setStatus(FaceEmbeddingStatus.CLUSTERED);
      faceEmbeddingRepository.save(embedding);
    }
  }

  private void markEmbeddingsIgnored(List<FaceEmbedding> embeddings) {
    for (FaceEmbedding embedding : embeddings) {
      embedding.setStatus(FaceEmbeddingStatus.IGNORED);
      faceEmbeddingRepository.save(embedding);
    }
  }

  private void publishUnknownPersonEvent(Person person, List<FaceEmbedding> embeddings) {
    String imageUrl =
        embeddings.stream()
            .filter(e -> e.getImageUrl() != null)
            .map(FaceEmbedding::getImageUrl)
            .findFirst()
            .orElse(null);

    UnknownPersonDetectedEventDto event =
        UnknownPersonDetectedEventDto.builder()
            .severity("info")
            .tags(List.of("unknown-person", "suggestion"))
            .deviceId(person.getDeviceId())
            .personId(person.getId())
            .time(
                LocalDateTime.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT))
            .title("Unknown person detected")
            .message(
                "An unrecognized person was seen multiple times. Would you like to add them as a known person?")
            .imageUrl(imageUrl)
            .build();

    unknownPersonEventPublisher.publishUnknownPersonDetected(event);
  }
}
