package com.is2.detective_manager.orchestrator.step.facerecognition;

import java.util.UUID;

/**
 * Colaborador del FaceRecognitionStep. Es lo único que cambia entre real y mock.
 * Recibe la imagen y el deviceId (para comparar contra las caras cargadas del device).
 */
public interface FaceRecognizer {

  boolean isKnown(byte[] image, UUID deviceId);
}
