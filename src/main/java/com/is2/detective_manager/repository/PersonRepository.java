package com.is2.detective_manager.repository;

import com.is2.detective_manager.model.entity.Person;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long> {

  Optional<Person> findByDeviceIdAndNormalizedName(UUID deviceId, String normalizedName);
}
