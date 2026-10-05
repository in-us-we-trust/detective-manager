package com.is2.detective_manager.orchestrator.step.facerecognition;

/**
 * Resultado del reconocimiento facial. El nombre sale de la misma respuesta del ML cuando la
 * persona es conocida; no implica una segunda llamada.
 */
public record FaceRecognitionOutcome(boolean known, String personName) {

  public static FaceRecognitionOutcome unknown() {
    return new FaceRecognitionOutcome(false, null);
  }

  public static FaceRecognitionOutcome known(String personName) {
    return new FaceRecognitionOutcome(true, personName);
  }
}
