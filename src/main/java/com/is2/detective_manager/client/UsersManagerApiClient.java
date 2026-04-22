package com.is2.detective_manager.client;

import com.is2.detective_manager.model.dto.NotifyIntruderRequestDto;

public interface UsersManagerApiClient {

  void notifyIntruder(NotifyIntruderRequestDto request);
}
