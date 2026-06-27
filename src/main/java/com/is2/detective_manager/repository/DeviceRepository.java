package com.is2.detective_manager.repository;

import com.is2.detective_manager.model.entity.Device;
import com.is2.detective_manager.model.entity.DeviceStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

  Optional<Device> findByIdAndStatusNot(UUID id, DeviceStatus status);
}
