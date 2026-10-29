package com.is2.detective_manager.validation.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.model.entity.AssessmentRecord;
import com.is2.detective_manager.model.entity.ChunkType;
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
import com.is2.detective_manager.repository.TrackRepository;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full request wiring against real analyzers and a refused connection, without an ML substitute.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("validacion")
class ValidationAuditFailureFlowIntegrationTest {

  private static final int UNAVAILABLE_ML_PORT = reserveThenCloseLoopbackPort();

  @TempDir static Path temporaryDirectory;

  @DynamicPropertySource
  static void isolatedConfiguration(DynamicPropertyRegistry properties) {
    properties.add(
        "spring.datasource.url",
        () ->
            "jdbc:h2:mem:audit_failure_flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
    properties.add("ml.service.base-url", () -> "http://127.0.0.1:" + UNAVAILABLE_ML_PORT);
    properties.add(
        "validation.audit.directory", () -> temporaryDirectory.resolve("audit").toString());
    properties.add(
        "validation.media.directory", () -> temporaryDirectory.resolve("media").toString());
    properties.add(
        "validation.notifications.file",
        () -> temporaryDirectory.resolve("notifications.jsonl").toString());
  }

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper mapper;
  @Autowired private ApplicationContext context;
  @Autowired private TrackRepository tracks;
  @Autowired private AssessmentRecordRepository assessments;
  @Autowired private ValidationAuditRecorder recorder;

  @ParameterizedTest
  @EnumSource(ChunkType.class)
  void unavailableMlStillAuditsEachRealStepAndPreservesTheExistingCreatedContract(ChunkType type)
      throws Exception {
    assertRealAnalyzers();
    UUID trackId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    String filename =
        switch (type) {
          case IMAGE -> "image.png";
          case AUDIO -> "audio.wav";
          case VIDEO -> "chunk_001.mp4";
        };
    byte[] payload =
        ("validation-audit-evidence-with-no-ml-listener-" + type.name())
            .getBytes(StandardCharsets.UTF_8);

    var response =
        mvc.perform(
                multipart(ValidationAuditFilter.EVENTS_PATH)
                    .file(
                        new MockMultipartFile(
                            "file", filename, "application/octet-stream", payload))
                    .param("trackId", trackId.toString())
                    .param("deviceId", deviceId.toString())
                    .param("mediaType", type.name()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.trackId").value(trackId.toString()))
            .andExpect(jsonPath("$.data.chunkType").value(type.name()))
            .andExpect(jsonPath("$.data.finalVerdict").value("CONTINUE"))
            .andExpect(jsonPath("$.data.finalSeverity").value("NORMAL"))
            .andReturn()
            .getResponse();

    UUID requestId = UUID.fromString(response.getHeader(ValidationAuditFilter.REQUEST_ID_HEADER));
    assertThat(response.getHeader(ValidationAuditFilter.AUDIT_STATUS_HEADER)).isEqualTo("RECORDED");
    assertThat(response.getHeader(ValidationAuditFilter.RUN_VALID_HEADER)).isEqualTo("false");
    Path auditPath =
        temporaryDirectory
            .resolve("audit")
            .resolve(trackId.toString())
            .resolve(requestId + ".json");
    String auditText = Files.readString(auditPath);
    JsonNode audit = mapper.readTree(auditText);
    Set<String> expectedSteps = expectedSteps(type);

    assertThat(audit.path("requestId").asText()).isEqualTo(requestId.toString());
    assertThat(audit.path("trackId").asText()).isEqualTo(trackId.toString());
    assertThat(audit.path("deviceId").asText()).isEqualTo(deviceId.toString());
    assertThat(audit.path("evidenceType").asText()).isEqualTo(type.name());
    assertThat(audit.path("filename").asText()).isEqualTo(filename);
    assertThat(audit.path("sha256").asText())
        .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
    assertThat(audit.path("httpStatus").asInt()).isEqualTo(201);
    assertThat(audit.path("analysisStatus").asText()).isEqualTo("FAILED");
    assertThat(audit.path("validForQuantitativeValidation").asBoolean()).isFalse();
    assertThat(audit.path("steps").size()).isEqualTo(expectedSteps.size());
    Set<String> auditedSteps = new HashSet<>();
    for (JsonNode step : audit.path("steps")) {
      auditedSteps.add(step.path("step").asText());
      assertThat(step.path("requestId").asText()).isEqualTo(requestId.toString());
      assertThat(step.path("quality").asText()).isEqualTo("FAILED");
      assertThat(step.path("signals").isEmpty()).isTrue();
      assertThat(step.path("error").path("type").asText()).isNotBlank();
      assertThat(step.path("startedAt").asText()).isNotBlank();
      assertThat(step.path("completedAt").asText()).isNotBlank();
    }
    assertThat(auditedSteps).isEqualTo(expectedSteps);

    assertThat(audit.path("mlResponses").size()).isEqualTo(expectedSteps.size());
    Set<String> transportSteps = new HashSet<>();
    for (JsonNode exchange : audit.path("mlResponses")) {
      transportSteps.add(exchange.path("step").asText());
      assertThat(exchange.path("requestId").asText()).isEqualTo(requestId.toString());
      assertThat(exchange.path("httpStatus").asInt()).isZero();
      assertThat(exchange.path("error").path("type").asText()).isNotBlank();
      assertThat(exchange.path("path").isMissingNode()).isTrue();
    }
    assertThat(transportSteps).isEqualTo(expectedSteps);
    assertThat(audit.path("errors").size()).isGreaterThanOrEqualTo(expectedSteps.size());
    assertThat(audit.path("consolidation").path("verdict").asText()).isEqualTo("CONTINUE");
    assertThat(audit.path("consolidation").path("severity").asText()).isEqualTo("NORMAL");
    assertThat(audit.path("consolidation").path("accumulatedSignals").isEmpty()).isTrue();
    assertThat(audit.path("consolidation").path("winningRule").path("default").asBoolean())
        .isTrue();
    assertThat(auditText)
        .doesNotContain(Base64.getEncoder().encodeToString(payload))
        .doesNotContain(new String(payload, StandardCharsets.UTF_8));

    var persistedTrack = tracks.findById(trackId).orElseThrow();
    assertThat(persistedTrack.getFinalVerdict()).isEqualTo(Verdict.CONTINUE);
    List<AssessmentRecord> persistedAssessments =
        assessments.findAllByTrackIdOrderByCreatedAtAsc(trackId);
    assertThat(persistedAssessments).hasSize(type == ChunkType.VIDEO ? 1 : 0);
    for (AssessmentRecord assessment : persistedAssessments) {
      assertThat(assessment.getRequestId()).isEqualTo(requestId);
      assertThat(assessment.getMediaType()).isEqualTo(type);
      assertThat(assessment.getVideoPresenceSeconds()).isNull();
    }
    assertThat(recorder.currentRequestId()).isNull();
  }

  private void assertRealAnalyzers() {
    assertThat(context.getBean(FaceRecognizer.class)).isInstanceOf(RealFaceRecognizer.class);
    assertThat(context.getBean(ObjectRecognizer.class)).isInstanceOf(RealObjectRecognizer.class);
    assertThat(context.getBean(ConversationAnalyzer.class))
        .isInstanceOf(RealConversationAnalyzer.class);
    assertThat(context.getBean(VideoAnalyzer.class)).isInstanceOf(RealVideoAnalyzer.class);
  }

  private Set<String> expectedSteps(ChunkType type) {
    return switch (type) {
      case IMAGE -> Set.of("FaceRecognitionStep", "ObjectRecognitionStep");
      case AUDIO -> Set.of("ConversationAnalysisStep");
      case VIDEO -> Set.of("VideoAnalysisStep");
    };
  }

  private static int reserveThenCloseLoopbackPort() {
    // No service or accept loop is started: all analyzer calls get a real connection refusal.
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    } catch (IOException error) {
      throw new IllegalStateException("No se pudo seleccionar el puerto de la prueba", error);
    }
  }
}
