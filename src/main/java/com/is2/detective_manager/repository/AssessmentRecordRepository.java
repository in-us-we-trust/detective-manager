package com.is2.detective_manager.repository;

import com.is2.detective_manager.model.entity.AssessmentRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentRecordRepository extends JpaRepository<AssessmentRecord, UUID> {

  /** Todos los assessments de un track, en orden de creación. */
  List<AssessmentRecord> findAllByTrackIdOrderByCreatedAtAsc(UUID trackId);
}
