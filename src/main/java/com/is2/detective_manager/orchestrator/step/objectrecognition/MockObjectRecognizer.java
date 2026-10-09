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
    log.info("[mock] Detección de objetos simulada");
    List<DetectedObject> objects =
        List.of(
            new DetectedObject("person", 0.95),
            new DetectedObject("covered_face", 0.88));
    Map<String, Integer> counts = Map.of("covered_face", 1);
    return new ObjectDetectionResult(objects, counts);
  }
}
