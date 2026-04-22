package com.is2.detective_manager.mapper;

import com.is2.detective_manager.model.dto.NotifyIntruderRequestDto;
import com.is2.detective_manager.model.dto.ReportEventRequestDto;
import com.is2.detective_manager.model.dto.ReportEventResponseDto;
import com.is2.detective_manager.model.entity.Event;
import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EventMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "deviceId", source = "deviceId")
  @Mapping(target = "intruder", source = "intruder")
  @Mapping(target = "imageUrl", source = "imageUrl")
  @Mapping(target = "eventType", source = "request.type")
  @Mapping(target = "timestamp", expression = "java(java.time.LocalDateTime.now())")
  Event toEvent(UUID deviceId, boolean intruder, String imageUrl, ReportEventRequestDto request);

  @Mapping(target = "eventId", source = "id")
  @Mapping(target = "intruder", source = "intruder")
  ReportEventResponseDto toReportEventResponseDto(Event event);

  NotifyIntruderRequestDto toNotifyIntruderRequestDto(String deviceId, String imageUrl);
}
