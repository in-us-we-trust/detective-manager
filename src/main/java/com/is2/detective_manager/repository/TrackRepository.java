package com.is2.detective_manager.repository;

import com.is2.detective_manager.model.entity.Track;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackRepository extends JpaRepository<Track, UUID> {

  // El id ES el trackId, así que buscar por trackId es findById(uuid).
  // findById, save, existsById, etc. ya vienen de JpaRepository.

  List<Track> findAllByDeviceId(UUID deviceId);
}
