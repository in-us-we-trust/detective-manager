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
    // TEMPORAL (prueba de paralelismo): delay para ver que corre en paralelo. QUITAR después.
    try {
      log.info("[mock] face: INICIO análisis (8000 ms) en thread {}", Thread.currentThread().getName());
      Thread.sleep(8000);
      log.info("[mock] face: FIN análisis en thread {}", Thread.currentThread().getName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    log.info("[mock] Reconocimiento facial simulado -> known=false");
    return false;
  }
}
