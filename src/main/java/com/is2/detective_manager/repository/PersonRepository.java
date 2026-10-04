package com.is2.detective_manager.repository;

import com.is2.detective_manager.model.entity.Person;
import com.is2.detective_manager.model.entity.PersonStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long> {

  Optional<Person> findByDeviceIdAndNormalizedName(UUID deviceId, String normalizedName);

  List<Person> findAllByDeviceIdOrderByCreatedAtAscIdAsc(UUID deviceId);

  Optional<Person> findByDeviceIdAndId(UUID deviceId, UUID personId);

  List<Person> findAllByStatusNotAndCreatedAtBefore(PersonStatus status, LocalDateTime threshold);
}
