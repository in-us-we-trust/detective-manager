package com.is2.detective_manager.orchestrator.step.objectrecognition;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Mock: devuelve objetos y counts fijos (basados en un ejemplo real del ML). */
@Slf4j
@Component
@Profile("mock-objects")
public class MockObjectRecognizer implements ObjectRecognizer {

  @Override
  public ObjectDetectionResult detectObjects(byte[] image) {
    // TEMPORAL (prueba de paralelismo): delay para ver que corre en paralelo. QUITAR después.
    try {
      log.info("[mock] object: INICIO análisis (5000 ms) en thread {}", Thread.currentThread().getName());
      Thread.sleep(5000);
      log.info("[mock] object: FIN análisis en thread {}", Thread.currentThread().getName());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    log.info("[mock] Detección de objetos simulada");
    List<DetectedObject> objects =
        List.of(
            new DetectedObject("person", 0.95),
            new DetectedObject("covered_face", 0.88),
            new DetectedObject("Firearm", 0.90));
    Map<String, Integer> counts = Map.of("person", 1, "covered_face", 1, "Firearm", 1);
    return new ObjectDetectionResult(objects, counts);
  }
}
