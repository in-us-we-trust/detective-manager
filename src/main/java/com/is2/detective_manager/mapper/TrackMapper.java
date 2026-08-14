package com.is2.detective_manager.mapper;

import com.is2.detective_manager.model.dto.TrackResponseDto;
import com.is2.detective_manager.model.entity.Track;

/** Convierte la entity Track al DTO de respuesta. */
public final class TrackMapper {

  private TrackMapper() {}

  public static TrackResponseDto toDto(Track track) {
    return TrackResponseDto.builder()
        .trackId(track.getId())
        .deviceId(track.getDeviceId())
        .status(track.getStatus() != null ? track.getStatus().name() : null)
        .currentStep(track.getCurrentStep())
        .accumulatedSignals(track.getAccumulatedSignals())
        .finalVerdict(track.getFinalVerdict() != null ? track.getFinalVerdict().name() : null)
        .finalSeverity(track.getFinalSeverity() != null ? track.getFinalSeverity().name() : null)
        .createdAt(track.getCreatedAt())
        .updatedAt(track.getUpdatedAt())
        .build();
  }
}
