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
  public List<String> detectObjects(String imageBase64) {
    log.info("[mock] Detección de objetos simulada -> [phone, backpack]");
    return List.of("phone", "backpack");
  }
}
