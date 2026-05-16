package com.is2.detective_manager.service;

import com.is2.detective_manager.model.dto.EventResponseDto;
import com.is2.detective_manager.model.dto.ReportEventRequestDto;
import com.is2.detective_manager.model.dto.ReportEventResponseDto;
import java.util.List;
import java.util.UUID;

public interface EventService {

  ReportEventResponseDto reportEvent(String deviceId, ReportEventRequestDto request);

  List<EventResponseDto> getEventsByDevice(UUID deviceId);

  List<EventResponseDto> getEventsByDevices(List<UUID> deviceIds);

  EventResponseDto getEvent(UUID eventId);
}
