package com.is2.detective_manager.mapper;

import com.is2.detective_manager.model.dto.EventResponseDto;
import com.is2.detective_manager.model.dto.FaceRecognitionClosestMatchDto;
import com.is2.detective_manager.model.dto.FaceRecognitionResultDto;
import com.is2.detective_manager.model.dto.ReportEventRequestDto;
import com.is2.detective_manager.model.dto.ReportEventResponseDto;
import com.is2.detective_manager.model.entity.Event;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EventMapper {

  String DEFAULT_EVENT_TYPE = "INTRUDER";

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "deviceId", source = "deviceId")
  @Mapping(target = "intruder", source = "intruder")
  @Mapping(target = "imageUrl", source = "imageUrl")
  @Mapping(target = "eventType", expression = "java(resolveEventType(request))")
  @Mapping(target = "faceKnown", source = "faceRecognition.known")
  @Mapping(target = "faceThreshold", source = "faceRecognition.threshold")
  @Mapping(target = "faceDistance", source = "faceRecognition.distance")
  @Mapping(target = "faceDistanceMetric", source = "faceRecognition.distanceMetric")
  @Mapping(target = "closestEmbeddingId", source = "faceRecognition.closestMatch.embeddingId")
  @Mapping(target = "closestPersonId", source = "faceRecognition.closestMatch.personId")
  @Mapping(target = "closestPersonName", source = "faceRecognition.closestMatch.personName")
  @Mapping(target = "faceModelName", source = "faceRecognition.modelName")
  @Mapping(target = "faceDetectorBackend", source = "faceRecognition.detectorBackend")
  @Mapping(target = "timestamp", expression = "java(java.time.LocalDateTime.now())")
  Event toEvent(
      UUID deviceId,
      boolean intruder,
      String imageUrl,
      ReportEventRequestDto request,
      FaceRecognitionResultDto faceRecognition);

  @Mapping(target = "eventId", source = "id")
  @Mapping(target = "intruder", source = "intruder")
  @Mapping(target = "faceRecognition", expression = "java(toFaceRecognitionResultDto(event))")
  ReportEventResponseDto toReportEventResponseDto(Event event);

  @Mapping(target = "faceRecognition", expression = "java(toFaceRecognitionResultDto(event))")
  EventResponseDto toEventResponseDto(Event event);

  default FaceRecognitionResultDto toFaceRecognitionResultDto(Event event) {
    if (event.getFaceKnown() == null) {
      return null;
    }

    FaceRecognitionClosestMatchDto closestMatch = null;
    if (event.getClosestEmbeddingId() != null
        || event.getClosestPersonId() != null
        || event.getClosestPersonName() != null) {
      closestMatch =
          FaceRecognitionClosestMatchDto.builder()
              .embeddingId(event.getClosestEmbeddingId())
              .personId(event.getClosestPersonId())
              .personName(event.getClosestPersonName())
              .build();
    }

    return FaceRecognitionResultDto.builder()
        .known(event.getFaceKnown())
        .threshold(event.getFaceThreshold())
        .distance(event.getFaceDistance())
        .distanceMetric(event.getFaceDistanceMetric())
        .closestMatch(closestMatch)
        .modelName(event.getFaceModelName())
        .detectorBackend(event.getFaceDetectorBackend())
        .build();
  }

  default String resolveEventType(ReportEventRequestDto request) {
    if (request.getType() == null || request.getType().isBlank()) {
      return DEFAULT_EVENT_TYPE;
    }

    return request.getType();
  }
}
