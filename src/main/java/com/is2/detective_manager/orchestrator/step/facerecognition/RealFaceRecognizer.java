package com.is2.detective_manager.orchestrator.step.facerecognition;

import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.dto.FaceRecognitionClosestMatchDto;
import com.is2.detective_manager.model.dto.FaceRecognitionFaceDto;
import com.is2.detective_manager.model.dto.FaceRecognitionResultDto;
import com.is2.detective_manager.model.dto.MlFaceRecognitionRequestDto;
import com.is2.detective_manager.service.PersonService;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Real: reconoce el rostro contra las caras conocidas del device. Trae los embeddings KNOWN, los
 * manda junto con la imagen al ML y conserva la identidad reconocida de cada rostro.
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
    if (result.getFaces() != null) {
      if (result.getFaces().isEmpty()) {
        throw invalidFaces();
      }
      Set<Integer> indices = new HashSet<>();
      for (FaceRecognitionFaceDto face : result.getFaces()) {
        if (face == null
            || face.getKnown() == null
            || face.getFaceIndex() == null
            || face.getFaceIndex() < 0
            || !indices.add(face.getFaceIndex())
            || (face.getDistance() != null
                && (!Double.isFinite(face.getDistance()) || face.getDistance() < 0))) {
          throw invalidFaces();
        }
      }
      FaceRecognitionOutcome outcome =
          FaceRecognitionOutcome.fromFaces(result.getFaces().stream().map(this::toFace).toList());
      if (outcome.known() != result.getKnown()) {
        throw invalidFaces();
      }
      return outcome;
    }

    // Backward compatibility with the single-face contract, which had no faces array.
    if (!result.getKnown()) {
      return FaceRecognitionOutcome.unknown();
    }

    FaceRecognitionClosestMatchDto closestMatch = result.getClosestMatch();
    String personName = closestMatch != null ? closestMatch.getPersonName() : null;
    return FaceRecognitionOutcome.known(personName);
  }

  private FaceRecognitionOutcome.Face toFace(FaceRecognitionFaceDto face) {
    FaceRecognitionClosestMatchDto match = face.getClosestMatch();
    return new FaceRecognitionOutcome.Face(
        face.getFaceIndex(),
        face.getBox(),
        face.getKnown(),
        face.getDistance(),
        match != null ? match.getEmbeddingId() : null,
        match != null ? match.getPersonId() : null,
        match != null ? match.getPersonName() : null);
  }

  private DetectiveException invalidFaces() {
    return new DetectiveException(
        ExceptionEnum.ML_SERVICE_ERROR,
        "El ML de reconocimiento facial devolvió resultados por rostro vacíos, incompletos o inconsistentes.");
  }
}
