package com.is2.detective_manager.orchestrator.step.objectrecognition;

/**
 * Colaborador del ObjectRecognitionStep. Detecta objetos en la imagen.
 * Devuelve los objetos (con confidence) y los counts por label.
 * Es lo único que cambia entre real y mock.
 */
public interface ObjectRecognizer {

  ObjectDetectionResult detectObjects(byte[] image);
}
