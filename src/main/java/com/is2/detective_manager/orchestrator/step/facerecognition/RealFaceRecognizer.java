package com.is2.detective_manager.orchestrator.step.facerecognition;

import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.FaceRecognitionClosestMatchDto;
import com.is2.detective_manager.model.dto.FaceRecognitionResultDto;
import com.is2.detective_manager.model.dto.MlFaceRecognitionRequestDto;
import com.is2.detective_manager.service.PersonService;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Real: reconoce el rostro contra las caras conocidas del device. Trae los embeddings KNOWN, los
 * manda junto con la imagen al ML, y devuelve si la persona es conocida y su nombre.
 */
@Slf4j
@Component
@Profile("!mock-face")
@RequiredArgsConstructor
public class RealFaceRecognizer implements FaceRecognizer {

  private final MlServiceApiClient mlServiceApiClient;
  private final PersonService personService;

  @Override
  public FaceRecognitionOutcome recognize(byte[] image, UUID deviceId) {
    List<FaceEmbeddingResponseDto> embeddings = personService.getFaceEmbeddings(deviceId);

    log.info("Reconocimiento facial para device {} con {} embeddings", deviceId, embeddings.size());

    String base64Image = Base64.getEncoder().encodeToString(image);
    MlFaceRecognitionRequestDto request = new MlFaceRecognitionRequestDto(base64Image, embeddings);
    FaceRecognitionResultDto result = mlServiceApiClient.recognizeFace(request);

    if (result == null || result.getKnown() == null) {
      throw new DetectiveException(
          ExceptionEnum.ML_SERVICE_ERROR,
          "El ML de reconocimiento facial devolvió una respuesta incompleta.");
    }
    if (!result.getKnown()) {
      return FaceRecognitionOutcome.unknown();
    }

    FaceRecognitionClosestMatchDto closestMatch = result.getClosestMatch();
    String personName = closestMatch != null ? closestMatch.getPersonName() : null;
    return FaceRecognitionOutcome.known(personName);
  }
}
