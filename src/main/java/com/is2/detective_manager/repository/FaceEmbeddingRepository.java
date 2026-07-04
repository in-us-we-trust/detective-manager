package com.is2.detective_manager.repository;

import com.is2.detective_manager.model.entity.FaceEmbedding;
import java.util.List;
import java.util.UUID;

import com.is2.detective_manager.model.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FaceEmbeddingRepository extends JpaRepository<FaceEmbedding, Long> {

  List<FaceEmbedding> findAllByPerson_DeviceIdOrderByCreatedAtAscIdAsc(UUID deviceId);

  List<FaceEmbedding> findAllByPerson_DeviceIdAndPerson_IdOrderByCreatedAtAsc(
      UUID deviceId, Long personId);

  void deleteAllByPerson(Person person);
}
