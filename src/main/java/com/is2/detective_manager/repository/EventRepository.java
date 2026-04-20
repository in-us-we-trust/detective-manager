package com.is2.detective_manager.repository;

import com.is2.detective_manager.model.entity.Event;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {}
