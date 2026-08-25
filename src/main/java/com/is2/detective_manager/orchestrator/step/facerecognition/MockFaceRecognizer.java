package com.is2.detective_manager.orchestrator.step.facerecognition;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Implementación mock: devuelve un resultado fijo. Se activa con el perfil mock-face. */
@Slf4j
@Component
@Profile("mock-face")
public class MockFaceRecognizer implements FaceRecognizer {

  @Override
  public boolean isKnown(byte[] image, UUID deviceId) {
    log.info("[mock] Reconocimiento facial simulado -> known=false");
    return false;
  }
}
