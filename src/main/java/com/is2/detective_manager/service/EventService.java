package com.is2.detective_manager.service;

import com.is2.detective_manager.model.dto.ReportEventRequestDto;
import com.is2.detective_manager.model.dto.ReportEventResponseDto;

public interface EventService {

  ReportEventResponseDto reportEvent(String deviceId, ReportEventRequestDto request);
}
