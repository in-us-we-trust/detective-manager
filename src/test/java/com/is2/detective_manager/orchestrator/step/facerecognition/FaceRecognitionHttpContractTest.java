package com.is2.detective_manager.orchestrator.step.facerecognition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.is2.detective_manager.client.MlServiceApiClientImpl;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.model.dto.FaceEmbeddingResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.RuleBasedDangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.service.PersonService;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Real HTTP client, decoder, facial step and rules; no external service or database is used. */
@ExtendWith(MockitoExtension.class)
class FaceRecognitionHttpContractTest {

  private static final UUID DEVICE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final String URL =
      "http://ml-contract/api/ml-service/v1/analysis/face-recognition";
  private static final byte[] IMAGE = {1, 2, 3};

  @Mock private PersonService persons;
  private MockRestServiceServer server;
  private FaceRecognitionStep step;

  @BeforeEach
  void setUp() {
    RestTemplate restTemplate = new RestTemplate();
    server = MockRestServiceServer.bindTo(restTemplate).build();
    step =
        new FaceRecognitionStep(
            new RealFaceRecognizer(
                new MlServiceApiClientImpl(restTemplate, "http://ml-contract"), persons));
    when(persons.getFaceEmbeddings(DEVICE_ID))
        .thenReturn(
            List.of(
                FaceEmbeddingResponseDto.builder()
                    .personName("Mateo")
                    .embedding(List.of(0.1, 0.2, 0.3))
                    .build()));
  }

  @AfterEach
  void verifyOnlyExpectedRequests() {
    server.verify();
  }

  @ParameterizedTest
  @CsvSource({
    "true,false,LLAMATIVO,CONTINUE",
    "true,true,NORMAL,SAFE",
    "false,false,LLAMATIVO,CONTINUE"
  })
  void groupContractPreservesPerFaceIdentityAndUsesExistingRules(
      boolean firstKnown, boolean secondKnown, String severity, String verdict) {
    String raw =
        """
        {"data":{"known":%s,"threshold":0.4,"distance":0.18,"distance_metric":"cosine",
          "closest_match":{"embedding_id":1,"person_id":"22222222-2222-4222-8222-222222222222","person_name":"Mateo"},
          "model_name":"ArcFace","detector_backend":"retinaface","faces":[
            {"face_index":0,"box":{"x":120,"y":80,"width":90,"height":110},
             "known":%s,"distance":0.18,"closest_match":{"embedding_id":1,
             "person_id":"22222222-2222-4222-8222-222222222222","person_name":"Mateo"}},
            {"face_index":1,"box":{"x":350,"y":85,"width":85,"height":105},
             "known":%s,"distance":0.61,"closest_match":{"embedding_id":2,
             "person_id":"33333333-3333-4333-8333-333333333333","person_name":"Juli"}}
          ]}}
        """
            .formatted(firstKnown || secondKnown, firstKnown, secondKnown);
    expectRecognition(raw);

    StepResult result = step.execute(context());
    List<Signal> signals = new ArrayList<>(result.getSignals());
    signals.add(Signal.NO_THREAT_OBJECT);
    var assessment = new RuleBasedDangerEvaluator().evaluate(signals);

    assertEquals(severity, assessment.getSeverity().name());
    assertEquals(verdict, assessment.getVerdict().name());
    assertTrue(result.getSignals().contains(Signal.MULTIPLE_PEOPLE));
    assertEquals(firstKnown || secondKnown, result.getSignals().contains(Signal.KNOWN_PERSON));
    assertEquals(!firstKnown || !secondKnown, result.getSignals().contains(Signal.UNKNOWN_PERSON));
    assertEquals(2, result.getData().get(FaceRecognitionStep.FACE_COUNT_DATA_KEY));
    var face = ((List<?>) result.getData().get(FaceRecognitionStep.FACES_DATA_KEY)).get(1);
    var second = (FaceRecognitionOutcome.Face) face;
    assertEquals(1, second.faceIndex());
    assertEquals(350, second.box().x());
    if (!secondKnown) {
      assertNull(second.personName());
      assertNull(second.personId());
      assertNull(second.embeddingId());
      assertEquals(0.61, second.distance());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void singleFaceContractAndLegacyContractKeepTheSameSignals(boolean known) {
    String match = "{\"embedding_id\":1,\"person_name\":\"Mateo\"}";
    expectRecognition("{\"data\":{\"known\":" + known + ",\"closest_match\":" + match + "}}");
    expectRecognition(
        "{\"data\":{\"known\":"
            + known
            + ",\"faces\":[{\"face_index\":0,\"known\":"
            + known
            + ",\"closest_match\":"
            + match
            + "}]}}");

    StepResult legacy = step.execute(context());
    StepResult current = step.execute(context());

    assertEquals(legacy.getSignals(), current.getSignals());
    assertEquals(
        legacy.getData().get(FaceRecognitionStep.PERSON_NAME_DATA_KEY),
        current.getData().get(FaceRecognitionStep.PERSON_NAME_DATA_KEY));
    assertFalse(current.getSignals().contains(Signal.MULTIPLE_PEOPLE));
    assertNull(current.getMessage());
  }

  @Test
  void nullCandidatesRemainUnknownWithoutEmbeddingRequests() {
    expectRecognition(
        """
        {"data":{"known":false,"distance":null,"closest_match":null,"faces":[
          {"face_index":0,"known":false,"distance":null,"closest_match":null},
          {"face_index":1,"known":false,"distance":null,"closest_match":null}]}}
        """);

    StepResult result = step.execute(context());

    assertEquals(List.of(Signal.UNKNOWN_PERSON, Signal.MULTIPLE_PEOPLE), result.getSignals());
    assertEquals(List.of(), result.getData().get(FaceRecognitionStep.PERSON_NAMES_DATA_KEY));
  }

  @Test
  void noFaces422FailsInsteadOfClaimingAnUnknownPerson() {
    server
        .expect(requestTo(URL))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"At least one face is required\"}"));

    assertThrows(DetectiveException.class, () -> step.execute(context()));
  }

  @Test
  void incompleteFaceInformationCannotFallBackToGlobalKnown() {
    expectRecognition("{\"data\":{\"known\":true,\"faces\":[{\"face_index\":0}]}}");

    assertThrows(DetectiveException.class, () -> step.execute(context()));
  }

  private void expectRecognition(String response) {
    server
        .expect(requestTo(URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    "{\"image\":\""
                        + Base64.getEncoder().encodeToString(IMAGE)
                        + "\",\"embeddings\":[{\"personName\":\"Mateo\",\"embedding\":[0.1,0.2,0.3]}]}"))
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
  }

  private StepContext context() {
    return StepContext.builder()
        .trackId(UUID.randomUUID().toString())
        .deviceId(DEVICE_ID)
        .mediaType(ChunkType.IMAGE)
        .payload(IMAGE)
        .accumulated(Map.of())
        .build();
  }
}
