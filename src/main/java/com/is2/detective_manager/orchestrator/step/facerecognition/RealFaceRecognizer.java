package com.is2.detective_manager.orchestrator.step.facerecognition;

import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.mapper.PersonMapper;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.FaceRecognitionResultDto;
import com.is2.detective_manager.model.dto.MlFaceRecognitionRequestDto;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Real: reconoce el rostro contra las caras cargadas del device. Trae los embeddings del device de
 * la DB, los manda junto con la imagen al ML, y devuelve si la persona es conocida.
 */
@Slf4j
@Component
@Profile("!mock-face")
@RequiredArgsConstructor
public class RealFaceRecognizer implements FaceRecognizer {

  private final MlServiceApiClient mlServiceApiClient;
  private final FaceEmbeddingRepository faceEmbeddingRepository;
  private final PersonMapper personMapper;

  @Override
  public boolean isKnown(byte[] image, UUID deviceId) {
    // 1) Traer los embeddings (caras conocidas) del device.
    List<FaceEmbeddingResponseDto> embeddings =
        faceEmbeddingRepository.findAllByPerson_DeviceIdOrderByCreatedAtAscIdAsc(deviceId).stream()
            .map(personMapper::toResponseDto)
            .toList();

    log.info("Reconocimiento facial para device {} con {} embeddings", deviceId, embeddings.size());

    // 2) Imagen a base64 (el ML de face espera base64, no bytes).
    String base64Image = Base64.getEncoder().encodeToString(image);

    // 3) Llamar al ML con imagen + embeddings.
    MlFaceRecognitionRequestDto request = new MlFaceRecognitionRequestDto(base64Image, embeddings);
    FaceRecognitionResultDto result = mlServiceApiClient.recognizeFace(request);

    // 4) Devolver si es conocida.
    return Boolean.TRUE.equals(result.getKnown());
  }
}
