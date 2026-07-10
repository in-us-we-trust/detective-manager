package com.is2.detective_manager.client;

import com.is2.detective_manager.model.dto.UnknownPersonDetectedEventDto;

public interface UnknownPersonEventPublisher {

  void publishUnknownPersonDetected(UnknownPersonDetectedEventDto event);
}
