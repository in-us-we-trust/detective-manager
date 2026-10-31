package com.is2.detective_manager.orchestrator.step.facerecognition;

import com.is2.detective_manager.model.dto.FaceRecognitionFaceDto.FaceBox;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Resultado del reconocimiento facial. Las identidades reconocidas salen de la misma respuesta del
 * ML; no implican una segunda llamada. known significa que al menos un rostro fue reconocido.
 */
public record FaceRecognitionOutcome(boolean known, String personName, List<Face> faces) {

  public FaceRecognitionOutcome(boolean known, String personName) {
    this(known, personName, List.of());
  }

  public FaceRecognitionOutcome {
    personName = recognizedName(known, personName);
    faces = List.copyOf(faces);
  }

  public static FaceRecognitionOutcome unknown() {
    return new FaceRecognitionOutcome(false, null);
  }

  public static FaceRecognitionOutcome known(String personName) {
    return new FaceRecognitionOutcome(true, personName);
  }

  public static FaceRecognitionOutcome fromFaces(List<Face> faces) {
    Face bestKnown =
        faces.stream()
            .filter(Face::known)
            .min(Comparator.comparing(Face::distance, Comparator.nullsLast(Double::compareTo)))
            .orElse(null);
    return new FaceRecognitionOutcome(
        bestKnown != null, bestKnown != null ? bestKnown.personName() : null, faces);
  }

  public boolean hasUnknownFaces() {
    return faces.isEmpty() ? !known : faces.stream().anyMatch(face -> !face.known());
  }

  public List<String> personNames() {
    if (faces.isEmpty()) {
      return personName == null ? List.of() : List.of(personName);
    }
    return faces.stream()
        .filter(Face::known)
        .map(Face::personName)
        .filter(name -> name != null)
        .distinct()
        .toList();
  }

  private static String recognizedName(boolean known, String name) {
    return known && name != null && !name.isBlank() ? name.strip() : null;
  }

  /** Only recognized identities are exposed; an unknown face never inherits its candidate. */
  public record Face(
      int faceIndex,
      FaceBox box,
      boolean known,
      Double distance,
      Long embeddingId,
      UUID personId,
      String personName) {

    public Face {
      embeddingId = known ? embeddingId : null;
      personId = known ? personId : null;
      personName = recognizedName(known, personName);
    }
  }
}
