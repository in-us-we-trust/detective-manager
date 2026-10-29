package com.is2.detective_manager.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudinary.Cloudinary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.model.entity.AssessmentRecord;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.FaceEmbeddingStatus;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalyzer;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.RealConversationAnalyzer;
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognizer;
import com.is2.detective_manager.orchestrator.step.facerecognition.RealFaceRecognizer;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectRecognizer;
import com.is2.detective_manager.orchestrator.step.objectrecognition.RealObjectRecognizer;
import com.is2.detective_manager.orchestrator.step.videoanalysis.RealVideoAnalyzer;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalyzer;
import com.is2.detective_manager.repository.AssessmentRecordRepository;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
import com.is2.detective_manager.repository.PersonRepository;
import com.is2.detective_manager.repository.TrackRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestTemplate;

/** Exercises the real HTTP adapters and persistence without running any external infrastructure. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("validacion")
class ValidationProfileIntegrationTest {

  private static final String EVENTS = "/api/detective-manager/v1/orchestrator/events";
  private static final String ML = "http://validation-ml/api/ml-service/v1/analysis";
  private static final String IMAGE_BASE64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=";
  private static final byte[] IMAGE = Base64.getDecoder().decode(IMAGE_BASE64);
  private static final String EMBEDDING_RESPONSE =
      """
      {"data":{"embedding":[0.125,-0.25,0.5],"model_name":"ArcFace","detector_backend":"retinaface"}}
      """;

  @TempDir static Path temporaryDirectory;

  @DynamicPropertySource
  static void validationProperties(DynamicPropertyRegistry properties) {
    properties.add("ml.service.base-url", () -> "http://validation-ml");
    properties.add(
        "validation.media.directory", () -> temporaryDirectory.resolve("media").toString());
    properties.add(
        "validation.notifications.file",
        () -> temporaryDirectory.resolve("notifications.jsonl").toString());
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper json;
  @Autowired private RestTemplate restTemplate;
  @Autowired private ApplicationContext context;
  @Autowired private DataSource dataSource;
  @Autowired private TrackRepository tracks;
  @Autowired private AssessmentRecordRepository assessments;
  @Autowired private FaceEmbeddingRepository embeddings;
  @Autowired private PersonRepository persons;

  private MockRestServiceServer ml;

  @BeforeEach
  void setUp() throws Exception {
    assessments.deleteAll();
    tracks.deleteAll();
    embeddings.deleteAll();
    persons.deleteAll();
    Files.deleteIfExists(notificationFile());
    ml = MockRestServiceServer.bindTo(restTemplate).bufferContent().ignoreExpectOrder(true).build();
  }

  @AfterEach
  void verifyMlRequests() {
    ml.verify();
  }

  @Test
  void profileUsesEmbeddedDatabaseAndRealMlAnalyzersWithoutCloudinaryOrRabbit() throws Exception {
    assertThat(context.getBeansOfType(Cloudinary.class)).isEmpty();
    assertThat(context.getBeansOfType(RabbitTemplate.class)).isEmpty();
    assertThat(context.getBean(FaceRecognizer.class)).isInstanceOf(RealFaceRecognizer.class);
    assertThat(context.getBean(ObjectRecognizer.class)).isInstanceOf(RealObjectRecognizer.class);
    assertThat(context.getBean(ConversationAnalyzer.class))
        .isInstanceOf(RealConversationAnalyzer.class);
    assertThat(context.getBean(VideoAnalyzer.class)).isInstanceOf(RealVideoAnalyzer.class);
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
    }
  }

  @Test
  void imageAudioAndVideoPreserveTrackEvidenceAndWriteDangerNotificationLocally() throws Exception {
    UUID deviceId = UUID.randomUUID();
    UUID trackId = UUID.randomUUID();
    ml.expect(requestTo(ML + "/face-recognition"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"image\":\"" + IMAGE_BASE64 + "\",\"embeddings\":[]}"))
        .andRespond(withSuccess("{\"data\":{\"known\":false}}", MediaType.APPLICATION_JSON));
    ml.expect(requestTo(ML + "/object-detection"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"image\":\"" + IMAGE_BASE64 + "\"}"))
        .andRespond(
            withSuccess(
                """
                {"data":{"objects":[{"label":"Firearm","confidence":0.95}],"counts":{"person":1}}}
                """,
                MediaType.APPLICATION_JSON));
    expectEmbedding();
    ml.expect(requestTo(ML + "/conversation"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
        .andExpect(content().string(containsString("name=\"visitor_audio\"")))
        .andExpect(content().string(containsString("validation-audio-payload")))
        .andRespond(
            withSuccess(
                """
                {"transcript":"Te voy a hacer daño", "evidences":[{"type":"THREAT","score":0.99}]}
                """,
                MediaType.APPLICATION_JSON));
    ml.expect(requestTo(ML + "/video"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
        .andExpect(content().string(containsString("name=\"video\"")))
        .andExpect(content().string(containsString("validation-video-payload")))
        .andRespond(withSuccess(videoResponse(), MediaType.APPLICATION_JSON));

    send(trackId, deviceId, "IMAGE", "image.png", IMAGE)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.finalVerdict").value("DANGER"))
        .andExpect(jsonPath("$.data.finalSeverity").value("PELIGROSO"));

    List<AssessmentRecord> imageAssessments =
        assessments.findAllByTrackIdOrderByCreatedAtAsc(trackId);
    assertThat(imageAssessments).hasSize(2);
    assertThat(imageAssessments)
        .extracting(AssessmentRecord::getStep)
        .containsExactlyInAnyOrder("FaceRecognitionStep", "ObjectRecognitionStep");
    String imageUrl = imageAssessments.get(0).getMediaUrl();
    assertThat(imageUrl).contains("/validation/media/");
    byte[] downloaded =
        mvc.perform(get(URI.create(imageUrl).getPath()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(downloaded).isEqualTo(IMAGE);
    FaceEmbedding orphan = embeddings.findAllByPersonIsNull().get(0);
    assertThat(orphan.getEmbedding()).containsExactly(0.125, -0.25, 0.5);
    assertThat(orphan.getStatus()).isEqualTo(FaceEmbeddingStatus.WAITING_BATCH);
    assertThat(orphan.getImageUrl()).isEqualTo(imageUrl);

    send(
            trackId,
            deviceId,
            "AUDIO",
            "audio.wav",
            "validation-audio-payload".getBytes(StandardCharsets.UTF_8))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.chunkType").value("AUDIO"))
        .andExpect(jsonPath("$.data.finalVerdict").value("DANGER"));
    send(
            trackId,
            deviceId,
            "VIDEO",
            "chunk_001.mp4",
            "validation-video-payload".getBytes(StandardCharsets.UTF_8))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.chunkType").value("VIDEO"))
        .andExpect(jsonPath("$.data.finalVerdict").value("DANGER"));

    var storedTrack = tracks.findById(trackId).orElseThrow();
    assertThat(storedTrack.getFinalVerdict()).isEqualTo(Verdict.DANGER);
    assertThat(storedTrack.getAccumulatedSignals())
        .contains("UNKNOWN_PERSON", "WEAPON_DETECTED", "THREAT", "NO_SUSPICIOUS_BEHAVIOR");
    List<AssessmentRecord> storedAssessments =
        assessments.findAllByTrackIdOrderByCreatedAtAsc(trackId);
    assertThat(storedAssessments).hasSize(4);
    AssessmentRecord video =
        storedAssessments.stream()
            .filter(assessment -> assessment.getMediaType() == ChunkType.VIDEO)
            .findFirst()
            .orElseThrow();
    assertThat(video.getVideoChunkKey()).isEqualTo("number:1");
    assertThat(video.getVideoChunkNumber()).isEqualTo(1L);
    assertThat(video.getVideoPresenceSeconds()).isEqualTo(1.0);

    List<String> notificationLines = Files.readAllLines(notificationFile());
    assertThat(notificationLines).hasSize(1);
    JsonNode notification = json.readTree(notificationLines.get(0));
    assertThat(notification.path("type").asText()).isEqualTo("INTRUDER_DETECTED");
    assertThat(notification.path("payload").path("deviceId").asText())
        .isEqualTo(deviceId.toString());
    assertThat(notification.path("payload").path("imageUrl").asText()).isEqualTo(imageUrl);
    assertThat(notification.path("payload").path("message").asText()).contains("armada");
  }

  @Test
  void knownPersonEmbeddingsRoundTripThroughH2AndReachRealRecognitionRequest() throws Exception {
    UUID deviceId = UUID.randomUUID();
    expectEmbedding();
    String created =
        mvc.perform(
                post("/api/detective-manager/v1/devices/{deviceId}/persons", deviceId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"Mateo", "images":["%s"]}
                        """
                            .formatted(IMAGE_BASE64)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String personId = json.readTree(created).path("data").path("id").asText();
    var storedEmbeddings =
        embeddings.findAllByPerson_DeviceIdAndPerson_IdOrderByCreatedAtAsc(
            deviceId, UUID.fromString(personId));
    assertThat(storedEmbeddings).hasSize(1);
    assertThat(storedEmbeddings.get(0).getEmbedding()).containsExactly(0.125, -0.25, 0.5);
    ml.verify();
    ml.reset();

    ml.expect(requestTo(ML + "/face-recognition"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"embeddings":[{"personId":"%s","personName":"Mateo","embedding":[0.125,-0.25,0.5]}]}
                    """
                        .formatted(personId)))
        .andRespond(
            withSuccess(
                """
                {"data":{"known":true,"closest_match":{"person_id":"%s","person_name":"Mateo"}}}
                """
                    .formatted(personId),
                MediaType.APPLICATION_JSON));
    ml.expect(requestTo(ML + "/object-detection"))
        .andRespond(
            withSuccess("{\"data\":{\"objects\":[],\"counts\":{}}}", MediaType.APPLICATION_JSON));

    send(UUID.randomUUID(), deviceId, "IMAGE", "known.png", IMAGE)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.finalVerdict").value("SAFE"))
        .andExpect(jsonPath("$.data.additionalInformation.personName").value("Mateo"));
    assertThat(Files.exists(notificationFile())).isFalse();
  }

  @Test
  void failedMlAudioIsReportedWithoutPersistingSuccessfulConversationEvidence() throws Exception {
    UUID trackId = UUID.randomUUID();
    ml.expect(requestTo(ML + "/conversation")).andRespond(withServerError());

    send(trackId, UUID.randomUUID(), "AUDIO", "audio.wav", new byte[] {1, 2, 3})
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.finalVerdict").value("CONTINUE"))
        .andExpect(jsonPath("$.data.message").value("No se pudo completar el análisis de audio"));
    assertThat(assessments.count()).isZero();
    assertThat(Files.exists(notificationFile())).isFalse();
  }

  private ResultActions send(
      UUID trackId, UUID deviceId, String mediaType, String filename, byte[] payload)
      throws Exception {
    return mvc.perform(
        multipart(EVENTS)
            .file(new MockMultipartFile("file", filename, "application/octet-stream", payload))
            .param("trackId", trackId.toString())
            .param("deviceId", deviceId.toString())
            .param("mediaType", mediaType));
  }

  private void expectEmbedding() {
    ml.expect(requestTo(ML + "/face-embedding"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess(EMBEDDING_RESPONSE, MediaType.APPLICATION_JSON));
  }

  private Path notificationFile() {
    return temporaryDirectory.resolve("notifications.jsonl");
  }

  private String videoResponse() {
    return """
        {"data":{
          "metadata":{"width":640,"height":480,"fps":1.0,"duration_s":1.0,"total_frames":1},
          "observations":[{
            "frame":0,"timestamp_s":0.0,"track_id":1,"confidence":0.95,
            "bbox_x1_pct":40.0,"bbox_y1_pct":20.0,"bbox_x2_pct":60.0,"bbox_y2_pct":80.0,
            "center_x_pct":50.0,"center_y_pct":50.0,"foot_x_pct":50.0,"foot_y_pct":80.0,
            "area_frame_pct":12.0
          }]
        }}
        """;
  }
}
