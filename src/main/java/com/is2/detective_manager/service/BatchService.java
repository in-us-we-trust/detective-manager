package com.is2.detective_manager.service;

import com.is2.detective_manager.model.dto.BatchResponseDto;
import org.springframework.transaction.annotation.Transactional;

public interface BatchService {

  BatchResponseDto runUnknownPersonsBatch(int deleted);

  @Transactional
  int deleteOldPersonInformation();
}
