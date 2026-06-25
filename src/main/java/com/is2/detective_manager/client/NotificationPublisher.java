package com.is2.detective_manager.client;

import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;

public interface NotificationPublisher {

  void publishIntruderDetected(IntruderNotificationMessageDto message);
}
