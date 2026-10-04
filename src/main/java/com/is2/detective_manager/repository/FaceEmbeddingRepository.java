package com.is2.detective_manager.repository;

import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.Person;
import com.is2.detective_manager.model.entity.PersonStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FaceEmbeddingRepository extends JpaRepository<FaceEmbedding, Long> {

  List<FaceEmbedding> findAllByPerson_DeviceIdAndPerson_StatusOrderByCreatedAtAscIdAsc(
      UUID deviceId, PersonStatus status);

  List<FaceEmbedding> findAllByPerson_DeviceIdAndPerson_IdOrderByCreatedAtAsc(
      UUID deviceId, UUID personId);

  void deleteAllByPerson(Person person);

  List<FaceEmbedding> findAllByPersonIsNull();

  @Modifying
  @Query("DELETE FROM FaceEmbedding fe WHERE fe.person IS NULL AND fe.createdAt < :threshold")
  int deleteOrphanEmbeddingsOlderThan(@Param("threshold") LocalDateTime threshold);
}
