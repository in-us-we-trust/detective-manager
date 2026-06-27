package com.is2.detective_manager.service.impl;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.mapper.DeviceMapper;
import com.is2.detective_manager.model.dto.CreateDeviceRequestDto;
import com.is2.detective_manager.model.dto.DeviceResponseDto;
import com.is2.detective_manager.model.dto.UpdateDeviceRequestDto;
import com.is2.detective_manager.model.entity.Device;
import com.is2.detective_manager.model.entity.DeviceStatus;
import com.is2.detective_manager.repository.DeviceRepository;
import com.is2.detective_manager.service.DeviceService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceServiceImpl implements DeviceService {

  private final DeviceRepository deviceRepository;
  private final DeviceMapper deviceMapper;

  @Override
  @Transactional
  public DeviceResponseDto createDevice(CreateDeviceRequestDto request) {
    log.info("Registering device with ID: {}", request.getId());

    if (request.getLastSeen().isBefore(request.getLastBoot())) {
      throw new DetectiveException(ExceptionEnum.INVALID_LAST_SEEN);
    }

    if (deviceRepository.existsById(request.getId())) {
      throw new DetectiveException(ExceptionEnum.DEVICE_ALREADY_EXISTS);
    }

    var device = deviceMapper.toEntity(request);
    device = deviceRepository.save(device);
    log.info("Device registered with ID: {}", device.getId());

    return deviceMapper.toResponseDto(device);
  }

  @Override
  @Transactional(readOnly = true)
  public DeviceResponseDto getDevice(UUID deviceId) {
    log.info("Fetching device with ID: {}", deviceId);

    return deviceMapper.toResponseDto(findDeviceOrThrow(deviceId));
  }

  @Override
  @Transactional
  public DeviceResponseDto updateDevice(UUID deviceId, UpdateDeviceRequestDto request) {
    log.info("Updating device with ID: {}", deviceId);

    var device = findDeviceOrThrow(deviceId);

    var recalculateUptime = request.getLastSeen() != null || request.getLastBoot() != null;

    deviceMapper.partialUpdate(request, device);

    if (recalculateUptime) {
      if (device.getLastSeen().isBefore(device.getLastBoot())) {
        throw new DetectiveException(ExceptionEnum.INVALID_LAST_SEEN);
      }
      device.setUptime(calculateUptime(device));
    }

    device = deviceRepository.save(device);
    log.info("Device updated with ID: {}", device.getId());

    return deviceMapper.toResponseDto(device);
  }

  @Override
  @Transactional
  public DeviceResponseDto claimDevice(UUID deviceId) {
    log.info("Claiming device with ID: {}", deviceId);

    return transitionStatus(
        deviceId, DeviceStatus.UNCLAIMED, DeviceStatus.CLAIMED, ExceptionEnum.DEVICE_NOT_CLAIMABLE);
  }

  @Override
  @Transactional
  public DeviceResponseDto unclaimDevice(UUID deviceId) {
    log.info("Unclaiming device with ID: {}", deviceId);

    return transitionStatus(
        deviceId,
        DeviceStatus.CLAIMED,
        DeviceStatus.UNCLAIMED,
        ExceptionEnum.DEVICE_NOT_UNCLAIMABLE);
  }

  @Override
  @Transactional
  public void updateLastSeen(UUID deviceId) {
    deviceRepository
        .findByIdAndStatusNot(deviceId, DeviceStatus.INACTIVE)
        .ifPresent(
            device -> {
              device.setLastSeen(Instant.now());
              device.setUptime(calculateUptime(device));
              deviceRepository.save(device);
              log.info("lastSeen updated for device: {}", deviceId);
            });
  }

  @Override
  @Transactional
  public void deleteDevice(UUID deviceId) {
    log.info("Deleting device with ID: {}", deviceId);

    var device = findDeviceOrThrow(deviceId);
    device.setStatus(DeviceStatus.INACTIVE);
    deviceRepository.save(device);
    log.info("Device {} marked as INACTIVE", deviceId);
  }

  private Device findDeviceOrThrow(UUID deviceId) {
    return deviceRepository
        .findByIdAndStatusNot(deviceId, DeviceStatus.INACTIVE)
        .orElseThrow(() -> new DetectiveException(ExceptionEnum.DEVICE_NOT_FOUND));
  }

  private DeviceResponseDto transitionStatus(
      UUID deviceId, DeviceStatus expected, DeviceStatus next, ExceptionEnum conflictException) {
    var device = findDeviceOrThrow(deviceId);

    if (device.getStatus() != expected) {
      throw new DetectiveException(conflictException);
    }

    device.setStatus(next);
    device = deviceRepository.save(device);
    log.info("Device {} transitioned from {} to {}", deviceId, expected, next);

    return deviceMapper.toResponseDto(device);
  }

  private long calculateUptime(Device device) {
    return Duration.between(device.getLastBoot(), device.getLastSeen()).getSeconds();
  }
}
