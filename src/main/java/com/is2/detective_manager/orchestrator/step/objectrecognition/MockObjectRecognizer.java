package com.is2.detective_manager.orchestrator.step.objectrecognition;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Implementación mock: devuelve una lista fija de objetos detectados. */
@Slf4j
@Component
@Profile("mock-objects")
public class MockObjectRecognizer implements ObjectRecognizer {

  @Override
  public List<String> detectObjects(byte[] image) {
    // TEMPORAL (solo para pruebas): delay artificial para ver que el assessment
    // del step 1 llega a la cola ANTES de que termine este step. QUITAR después.
    try {
      log.info("[mock] Simulando análisis lento de objetos (delay de prueba)...");
      Thread.sleep(10_000); // 10 segundos
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    log.info("[mock] Detección de objetos simulada -> [phone, weapon]");
    return List.of("phone", "weapon");
  }
}