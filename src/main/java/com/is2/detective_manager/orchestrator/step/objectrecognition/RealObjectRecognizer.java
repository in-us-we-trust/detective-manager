package com.is2.detective_manager.orchestrator.step.objectrecognition;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Implementación real: todavía no hay ML de detección de objetos. Placeholder. */
@Slf4j
@Component
@Profile("!mock-objects")
public class RealObjectRecognizer implements ObjectRecognizer {

  @Override
  public List<String> detectObjects(String imageBase64) {
    log.info("[real] Detección de objetos contra el ml-service");
    throw new UnsupportedOperationException("RealObjectRecognizer todavía no implementado");
  }
}
