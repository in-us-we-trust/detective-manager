package com.is2.detective_manager.mapper;

import com.is2.detective_manager.model.dto.TrackResponseDto;
import com.is2.detective_manager.model.entity.Track;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Convierte la entity Track al DTO de respuesta, incluyendo el título para la app. */
@Component
@RequiredArgsConstructor
public class TrackMapper {

  private final TrackMessageBuilder trackMessageBuilder;

  public TrackResponseDto toDto(Track track) {
    TrackDisplay display = trackMessageBuilder.build(track.getAccumulatedSignals());
    return TrackResponseDto.builder()
        .code(display.code())
        .title(display.title())
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
