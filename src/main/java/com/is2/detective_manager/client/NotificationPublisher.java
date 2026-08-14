package com.is2.detective_manager.client;

import com.is2.detective_manager.model.dto.AssessmentMessageDto;
import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;

public interface NotificationPublisher {

  void publishIntruderDetected(IntruderNotificationMessageDto message);

  /** Publica el resultado de un step a la cola de assessments. */
  void publishAssessment(AssessmentMessageDto message);
}
