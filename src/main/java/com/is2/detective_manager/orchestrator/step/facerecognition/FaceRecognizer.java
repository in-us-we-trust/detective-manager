package com.is2.detective_manager.orchestrator.step.facerecognition;

/**
 * Colaborador del FaceRecognitionStep. Es lo único que cambia entre real y mock.
 * El step siempre es el mismo; se swappea esta pieza por perfil de Spring.
 */
public interface FaceRecognizer {

  boolean isKnown(String imageBase64);
}
