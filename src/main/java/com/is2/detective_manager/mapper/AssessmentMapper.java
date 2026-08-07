package com.is2.detective_manager.mapper;

import com.is2.detective_manager.model.dto.AssessmentDto;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;

/** Convierte el Assessment interno del orquestador al DTO que viaja por HTTP. */
public final class AssessmentMapper {

  private AssessmentMapper() {}

  public static AssessmentDto toDto(Assessment a) {
    return AssessmentDto.builder()
        .status(a.getStatus().name())
        .severity(a.getSeverity().name())
        .message(a.getMessage())
        .verdict(a.getVerdict().name())
        .build();
  }
}
