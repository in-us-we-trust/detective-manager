package com.is2.detective_manager.orchestrator.step.objectrecognition;

import java.util.List;

/**
 * Colaborador del ObjectRecognitionStep. Detecta objetos en la imagen (ej. armas).
 * Es lo único que cambia entre real y mock.
 */
public interface ObjectRecognizer {

  List<String> detectObjects(String imageBase64);
}
