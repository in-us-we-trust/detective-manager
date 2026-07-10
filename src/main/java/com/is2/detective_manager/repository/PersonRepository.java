package com.is2.detective_manager.repository;

import com.is2.detective_manager.model.entity.Person;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.is2.detective_manager.model.entity.PersonStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long> {

  Optional<Person> findByDeviceIdAndNormalizedName(UUID deviceId, String normalizedName);

  List<Person> findAllByDeviceIdOrderByCreatedAtAscIdAsc(UUID deviceId);

  Optional<Person> findByDeviceIdAndId(UUID deviceId, Long personId);

  List<Person> findAllByStatusNotAndCreatedAtBefore(PersonStatus known, LocalDateTime threshold);
}
