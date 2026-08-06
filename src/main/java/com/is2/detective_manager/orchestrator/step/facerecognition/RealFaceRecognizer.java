package com.is2.detective_manager.orchestrator.step.facerecognition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Implementación real: pega al ml-service. Activa salvo que el perfil mock-face esté encendido. */
@Slf4j
@Component
@Profile("!mock-face")
@RequiredArgsConstructor
public class RealFaceRecognizer implements FaceRecognizer {

  // TODO: inyectar MlServiceApiClient cuando enganchemos el ML real.
  // private final MlServiceApiClient mlServiceApiClient;

  @Override
  public boolean isKnown(String imageBase64) {
    log.info("[real] Reconociendo rostro contra el ml-service");
    // TODO: construir el request con los embeddings del device y llamar a recognizeFace(...)
    throw new UnsupportedOperationException("RealFaceRecognizer todavía no implementado");
  }
}
