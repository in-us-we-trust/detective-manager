package com.is2.detective_manager.orchestrator.step.objectrecognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.model.dto.ObjectDetectionResponseDto;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class RealObjectRecognizerTest {

  private static final String URL = "http://ml-service/api/ml-service/v1/analysis/object-detection";

  @ParameterizedTest
  @ValueSource(strings = {"null", "{}", "{\"data\":{}}"})
  void detectObjects_missingResponseFieldsFailInsteadOfReturningNoThreats(String json)
      throws Exception {
    RealObjectRecognizer recognizer = recognizer(json);

    assertThrows(DetectiveException.class, () -> recognizer.detectObjects(new byte[] {1}));
  }

  @Test
  void detectObjects_validEmptyObjectsAndCountsAreACompletedAnalysis() throws Exception {
    ObjectDetectionResult result =
        recognizer("{\"data\":{\"objects\":[],\"counts\":{}}}").detectObjects(new byte[] {1});

    assertEquals(AnalysisQuality.VALID, result.quality());
    assertEquals(List.of(), result.objects());
    assertEquals(Map.of(), result.counts());
  }

  @Test
  void detectObjects_missingCountsPreservesValidWeaponAsIncomplete() throws Exception {
    RealObjectRecognizer recognizer =
        recognizer("{\"data\":{\"objects\":[{\"label\":\"Firearm\",\"confidence\":0.9}]}}");

    StepResult result =
        new ObjectRecognitionStep(recognizer)
            .execute(StepContext.builder().payload(new byte[] {1}).build());

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertEquals(List.of(Signal.WEAPON_DETECTED), result.getSignals());
  }

  @Test
  void detectObjects_missingObjectsPreservesValidCountsWithoutReassurance() throws Exception {
    RealObjectRecognizer recognizer = recognizer("{\"data\":{\"counts\":{\"person\":2}}}");

    StepResult result =
        new ObjectRecognitionStep(recognizer)
            .execute(StepContext.builder().payload(new byte[] {1}).build());

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertEquals(List.of(Signal.MULTIPLE_PEOPLE), result.getSignals());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{\"label\":\"Firearm\"}",
        "{\"confidence\":0.9}",
        "{\"label\":\"\",\"confidence\":0.9}",
        "{\"label\":\"person\",\"confidence\":-0.1}",
        "{\"label\":\"person\",\"confidence\":1.1}"
      })
  void detectObjects_partialObjectsRetainValidWeaponAndMarkIncomplete(String invalidObject)
      throws Exception {
    String json =
        "{\"data\":{\"objects\":["
            + invalidObject
            + ",{\"label\":\"Firearm\",\"confidence\":0.9}],\"counts\":{\"Firearm\":1}}}";

    ObjectDetectionResult result = recognizer(json).detectObjects(new byte[] {1});

    assertEquals(AnalysisQuality.INSUFFICIENT, result.quality());
    assertEquals(List.of(new DetectedObject("Firearm", 0.9)), result.objects());
    assertEquals(Map.of("Firearm", 1), result.counts());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"person\":-1}", "{\"person\":null}", "{\"\":1}"})
  void detectObjects_corruptCountsMarkAnalysisIncomplete(String counts) throws Exception {
    ObjectDetectionResult result =
        recognizer("{\"data\":{\"objects\":[],\"counts\":" + counts + "}}")
            .detectObjects(new byte[] {1});

    assertEquals(AnalysisQuality.INSUFFICIENT, result.quality());
    assertTrue(result.counts().isEmpty());
  }

  @Test
  void detectObjects_nonFiniteConfidenceIsInsufficient() {
    ObjectDetectionResponseDto.DetectedObjectDto object =
        new ObjectDetectionResponseDto.DetectedObjectDto();
    object.setLabel("Firearm");
    object.setConfidence(Double.POSITIVE_INFINITY);
    ObjectDetectionResponseDto.Data data = new ObjectDetectionResponseDto.Data();
    data.setObjects(List.of(object));
    data.setCounts(Map.of());
    ObjectDetectionResponseDto response = new ObjectDetectionResponseDto();
    response.setData(data);

    ObjectDetectionResult result = recognizer(response).detectObjects(new byte[] {1});

    assertEquals(AnalysisQuality.INSUFFICIENT, result.quality());
    assertTrue(result.objects().isEmpty());
  }

  @Test
  void detectObjects_mlRequestFailureDoesNotBecomeAValidEmptyDetection() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForObject(
            eq(URL), any(HttpEntity.class), eq(ObjectDetectionResponseDto.class)))
        .thenThrow(new RestClientException("unavailable"));
    RealObjectRecognizer recognizer = new RealObjectRecognizer(restTemplate, "http://ml-service");

    assertThrows(DetectiveException.class, () -> recognizer.detectObjects(new byte[] {1}));
  }

  private RealObjectRecognizer recognizer(String json) throws Exception {
    return recognizer(new ObjectMapper().readValue(json, ObjectDetectionResponseDto.class));
  }

  private RealObjectRecognizer recognizer(ObjectDetectionResponseDto response) {
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForObject(
            eq(URL), any(HttpEntity.class), eq(ObjectDetectionResponseDto.class)))
        .thenReturn(response);
    return new RealObjectRecognizer(restTemplate, "http://ml-service");
  }
}
