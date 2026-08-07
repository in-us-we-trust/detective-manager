package com.is2.detective_manager.orchestrator.step.facerecognition;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Implementación mock: devuelve un resultado fijo. Se activa con el perfil mock-face. */
@Slf4j
@Component
@Profile("mock-face")
public class MockFaceRecognizer implements FaceRecognizer {

  @Override
  public boolean isKnown(String imageBase64) {
    log.info("[mock] Reconocimiento facial simulado -> known=true");
    return false; // Simulamos que la cara es desconocida para probar la señal UNKNOWN_PERSON.
  }
}
