package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.client.UsersManagerApiClient;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.mapper.EventMapper;
import com.is2.detective_manager.model.dto.*;
import com.is2.detective_manager.model.dto.EventResponseDto;
import com.is2.detective_manager.model.entity.Event;
import com.is2.detective_manager.repository.EventRepository;
import com.is2.detective_manager.service.EventService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventServiceImpl implements EventService {

  private final MlServiceApiClient mlServiceApiClient;
  private final UsersManagerApiClient usersManagerApiClient;
  private final CloudinaryStorageClient cloudinaryStorageClient;
  private final EventRepository eventRepository;
  private final EventMapper eventMapper;

  @Override
  @Transactional
  public ReportEventResponseDto reportEvent(String deviceId, ReportEventRequestDto request) {
    log.info("Processing security event from device: {}", deviceId);

    UUID deviceUuid = parseDeviceId(deviceId);

    MlAnalysisRequestDto mlRequest =
        new MlAnalysisRequestDto(request.getImage(), request.getType());
    boolean isIntruder = mlServiceApiClient.analyzeIntruder(mlRequest);
    log.info("ML analysis result for device {}: isIntruder={}", deviceId, isIntruder);

    String imageUrl = cloudinaryStorageClient.uploadEventImage(request.getImage(), deviceUuid);

    Event event = eventMapper.toEvent(deviceUuid, isIntruder, imageUrl, request);
    event = eventRepository.save(event);
    log.info("Event saved with ID: {}", event.getId());

    if (isIntruder) {
      log.info("Intruder detected for device: {}, sending notification", deviceId);
      NotifyIntruderRequestDto notifyRequest =
          eventMapper.toNotifyIntruderRequestDto(deviceId, imageUrl);
      usersManagerApiClient.notifyIntruder(notifyRequest);
    }

    return eventMapper.toReportEventResponseDto(event);
  }

  @Override
  @Transactional(readOnly = true)
  public List<EventResponseDto> getEventsByDevice(UUID deviceId) {
    log.info("Fetching events for device: {}", deviceId);

    return eventRepository.findAllByDeviceId(deviceId).stream()
        .map(eventMapper::toEventResponseDto)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<EventResponseDto> getEventsByDevices(List<UUID> deviceIds) {
    log.info("Fetching events for devices: {}", deviceIds);

    if (deviceIds == null || deviceIds.isEmpty()) {
      return eventRepository.findAll().stream().map(eventMapper::toEventResponseDto).toList();
    }

    return eventRepository.findAllByDeviceIdIn(deviceIds).stream()
        .map(eventMapper::toEventResponseDto)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public EventResponseDto getEvent(UUID eventId) {
    log.info("Fetching event with ID: {}", eventId);

    Event event =
        eventRepository
            .findById(eventId)
            .orElseThrow(() -> new DetectiveException(ExceptionEnum.EVENT_NOT_FOUND));

    return eventMapper.toEventResponseDto(event);
  }

  private UUID parseDeviceId(String deviceId) {
    try {
      return UUID.fromString(deviceId);
    } catch (IllegalArgumentException e) {
      throw new DetectiveException(ExceptionEnum.INVALID_DEVICE_ID);
    }
  }
}
