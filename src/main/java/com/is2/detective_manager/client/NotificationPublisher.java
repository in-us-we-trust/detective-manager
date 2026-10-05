package com.is2.detective_manager.client;

import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;
import com.is2.detective_manager.model.dto.UnknownPersonDetectedEventDto;

public interface NotificationPublisher {

  void publishIntruderDetected(IntruderNotificationMessageDto message);

  void publishUnknownPersonDetected(UnknownPersonDetectedEventDto event);
}
