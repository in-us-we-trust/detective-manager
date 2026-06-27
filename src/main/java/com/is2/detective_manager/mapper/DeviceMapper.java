package com.is2.detective_manager.mapper;

import com.is2.detective_manager.model.dto.CreateDeviceRequestDto;
import com.is2.detective_manager.model.dto.DeviceResponseDto;
import com.is2.detective_manager.model.dto.UpdateDeviceRequestDto;
import com.is2.detective_manager.model.entity.Device;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface DeviceMapper {

  int DEFAULT_DETECTION_THRESHOLD = 90;

  @Mapping(
      target = "status",
      expression = "java(com.is2.detective_manager.model.entity.DeviceStatus.UNCLAIMED)")
  @Mapping(
      target = "uptime",
      expression =
          "java(java.time.Duration.between(request.getLastBoot(), request.getLastSeen()).getSeconds())")
  @Mapping(
      target = "detectionThreshold",
      expression =
          "java(request.getDetectionThreshold() != null ? request.getDetectionThreshold() : DEFAULT_DETECTION_THRESHOLD)")
  Device toEntity(CreateDeviceRequestDto request);

  @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "uptime", ignore = true)
  void partialUpdate(UpdateDeviceRequestDto request, @MappingTarget Device device);

  DeviceResponseDto toResponseDto(Device device);
}
