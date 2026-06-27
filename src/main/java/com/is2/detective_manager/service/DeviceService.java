package com.is2.detective_manager.service;

import com.is2.detective_manager.model.dto.CreateDeviceRequestDto;
import com.is2.detective_manager.model.dto.DeviceResponseDto;
import com.is2.detective_manager.model.dto.UpdateDeviceRequestDto;
import java.util.UUID;

public interface DeviceService {

  DeviceResponseDto createDevice(CreateDeviceRequestDto request);

  DeviceResponseDto updateDevice(UUID deviceId, UpdateDeviceRequestDto request);

  DeviceResponseDto getDevice(UUID deviceId);

  void updateLastSeen(UUID deviceId);

  DeviceResponseDto claimDevice(UUID deviceId);

  DeviceResponseDto unclaimDevice(UUID deviceId);

  void deleteDevice(UUID deviceId);
}
