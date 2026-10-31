package com.is2.detective_manager.validation.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.RuleBasedDangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoPresenceChunk;
import com.is2.detective_manager.repository.AssessmentRecordRepository;
import com.is2.detective_manager.repository.TrackRepository;
import com.is2.detective_manager.service.TrackPersistenceService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real H2, repositories, evaluator and recorder; no inference endpoint is contacted or replaced.
 */
@SpringBootTest
@ActiveProfiles("validacion")
class AuditConsolidationIntegrationTest {

  @TempDir static Path directory;

  @DynamicPropertySource
  static void auditProperties(DynamicPropertyRegistry properties) {
    properties.add("validation.audit.directory", () -> directory.resolve("audit").toString());
    properties.add("validation.media.directory", () -> directory.resolve("media").toString());
    properties.add(
        "validation.notifications.file", () -> directory.resolve("notifications.jsonl").toString());
    properties.add(
        "spring.datasource.url",
        () ->
            "jdbc:h2:mem:audit_consolidation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
    properties.add("ml.service.base-url", () -> "http://127.0.0.1:9");
  }

  @Autowired private ValidationAuditRecorder recorder;
  @Autowired private TrackPersistenceService persistence;
  @Autowired private TrackRepository tracks;
  @Autowired private AssessmentRecordRepository assessments;
  @Autowired private ObjectMapper mapper;
  @Autowired private DataSource dataSource;
  @Autowired private RuleBasedDangerEvaluator evaluator;

  @BeforeEach
  void clearDatabase() {
    assessments.deleteAll();
    tracks.deleteAll();
  }

  @Test
  void eachEvidenceHasItsOwnCommittedSnapshotAndDiskSurvivesDatabaseReset() throws Exception {
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
    }
    UUID trackId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    UUID imageId = UUID.randomUUID();
    UUID audioId = UUID.randomUUID();
    recordEvidence(
        recorder,
        trackId,
        deviceId,
        imageId,
        ChunkType.IMAGE,
        "FaceRecognitionStep",
        List.of(Signal.UNKNOWN_PERSON),
        null);
    byte[] imageRecord = Files.readAllBytes(recordPath(trackId, imageId));
    recordEvidence(
        recorder,
        trackId,
        deviceId,
        audioId,
        ChunkType.AUDIO,
        "ConversationAnalysisStep",
        List.of(Signal.THREAT),
        null);

    JsonNode image = read(trackId, imageId);
    JsonNode audio = read(trackId, audioId);
    assertThat(image.path("consolidation").path("accumulatedSignals").toString())
        .contains("UNKNOWN_PERSON")
        .doesNotContain("THREAT");
    assertThat(image.path("consolidation").path("verdict").asText()).isEqualTo("CONTINUE");
    assertThat(image.path("consolidation").path("winningRule").path("requires").toString())
        .contains("UNKNOWN_PERSON");
    assertThat(audio.path("consolidation").path("accumulatedSignals").toString())
        .contains("UNKNOWN_PERSON", "THREAT");
    assertThat(audio.path("consolidation").path("winningRule").path("requires").toString())
        .isEqualTo("[\"THREAT\"]");
    assertThat(audio.path("consolidation").path("verdict").asText()).isEqualTo("DANGER");
    assertThat(audio.path("consolidation").path("severity").asText()).isEqualTo("PELIGROSO");
    assertThat(tracks.findById(trackId).orElseThrow().getFinalVerdict()).isEqualTo(Verdict.DANGER);
    assertThat(assessments.findAllByTrackIdOrderByCreatedAtAsc(trackId))
        .extracting(record -> record.getRequestId())
        .containsExactly(imageId, audioId);

    // H2 state is cleared as it is on restart; audit is deliberately independent of it.
    assessments.deleteAll();
    tracks.deleteAll();
    assertThat(tracks.count()).isZero();
    var restartedRecorder =
        new ValidationAuditRecorder(mapper, directory.resolve("audit").toString());
    UUID newRequestId = UUID.randomUUID();
    recordEvidence(
        restartedRecorder,
        trackId,
        deviceId,
        newRequestId,
        ChunkType.IMAGE,
        "FaceRecognitionStep",
        List.of(Signal.UNKNOWN_PERSON),
        null);
    assertThat(Files.readAllBytes(recordPath(trackId, imageId))).isEqualTo(imageRecord);
    assertThat(Files.exists(recordPath(trackId, audioId))).isTrue();
    assertThat(
            read(trackId, newRequestId).path("consolidation").path("accumulatedSignals").toString())
        .doesNotContain("THREAT");
  }

  @Test
  void videoAuditIncludesPresenceSignalDerivedFromCommittedAssessments() throws Exception {
    UUID trackId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    LocalDateTime received = LocalDateTime.of(2026, 10, 29, 10, 0);
    UUID first = null;
    UUID last = null;
    for (int number = 1; number <= 4; number++) {
      UUID requestId = UUID.randomUUID();
      if (number == 1) {
        first = requestId;
      }
      last = requestId;
      recordEvidence(
          recorder,
          trackId,
          deviceId,
          requestId,
          ChunkType.VIDEO,
          "VideoAnalysisStep",
          List.of(Signal.NO_SUSPICIOUS_BEHAVIOR),
          new VideoPresenceChunk(
              "number:" + number, (long) number, 5.0, received.plusSeconds(number)));
    }
    assertThat(read(trackId, first).path("consolidation").path("accumulatedSignals").toString())
        .doesNotContain("PROLONGED_VISIT_PRESENCE");
    JsonNode lastSnapshot = read(trackId, last).path("consolidation");
    assertThat(lastSnapshot.path("accumulatedSignals").toString())
        .contains("PROLONGED_VISIT_PRESENCE");
    assertThat(lastSnapshot.path("winningRule").path("requires").toString())
        .contains("PROLONGED_VISIT_PRESENCE");
    assertThat(lastSnapshot.path("severity").asText()).isEqualTo("LLAMATIVO");
    assertThat(lastSnapshot.path("verdict").asText()).isEqualTo("CONTINUE");
    assertThat(assessments.findAllByTrackIdOrderByCreatedAtAsc(trackId)).hasSize(4);
  }

  @ParameterizedTest
  @EnumSource(
      value = Signal.class,
      names = {"KNOWN_PERSON", "UNKNOWN_PERSON"})
  void videoAuditPreservesApproachesAndReassuranceWithoutAddingRisk(Signal identity)
      throws Exception {
    UUID trackId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    UUID imageId = UUID.randomUUID();
    UUID videoId = UUID.randomUUID();
    recordEvidence(
        recorder,
        trackId,
        deviceId,
        imageId,
        ChunkType.IMAGE,
        "FaceRecognitionStep",
        List.of(identity, Signal.NO_THREAT_OBJECT),
        null);
    JsonNode image = read(trackId, imageId).path("consolidation");

    recordEvidence(
        recorder,
        trackId,
        deviceId,
        videoId,
        ChunkType.VIDEO,
        "VideoAnalysisStep",
        List.of(Signal.APPROACHING_CAMERA, Signal.FAST_APPROACH, Signal.NO_SUSPICIOUS_BEHAVIOR),
        new VideoPresenceChunk("number:1", 1L, 5.0, LocalDateTime.now()));

    JsonNode document = read(trackId, videoId);
    JsonNode video = document.path("consolidation");
    assertThat(document.path("steps").get(0).path("signals").toString())
        .contains("APPROACHING_CAMERA", "FAST_APPROACH", "NO_SUSPICIOUS_BEHAVIOR");
    assertThat(video.path("accumulatedSignals").toString())
        .contains(identity.name(), "APPROACHING_CAMERA", "FAST_APPROACH", "NO_SUSPICIOUS_BEHAVIOR");
    assertThat(video.path("severity")).isEqualTo(image.path("severity"));
    assertThat(video.path("verdict")).isEqualTo(image.path("verdict"));
    assertThat(video.path("winningRule").path("requires").toString())
        .doesNotContain("APPROACHING_CAMERA", "FAST_APPROACH");
    assertThat(video.path("message").asText())
        .contains("No se detectó nada extraño en el video")
        .doesNotContain("Revisá antes de abrir");
    assertThat(assessments.findAllByTrackIdOrderByCreatedAtAsc(trackId))
        .extracting(record -> record.getSignals())
        .anySatisfy(signals -> assertThat(signals).contains("APPROACHING_CAMERA", "FAST_APPROACH"));
  }

  private void recordEvidence(
      ValidationAuditRecorder audit,
      UUID trackId,
      UUID deviceId,
      UUID requestId,
      ChunkType type,
      String step,
      List<Signal> signals,
      VideoPresenceChunk presence) {
    // For the restarted recorder, bind the same actual evaluator/persistence hooks to that
    // instance.
    var actualPersistence =
        (com.is2.detective_manager.service.impl.TrackPersistenceServiceImpl)
            org.springframework.test.util.AopTestUtils.getUltimateTargetObject(persistence);
    actualPersistence.setValidationAuditRecorder(audit);
    evaluator.setValidationAuditRecorder(audit);
    try (var scope = audit.open(requestId)) {
      String filename =
          presence == null ? "audit-fixture" : "chunk_%03d.mp4".formatted(presence.number());
      audit.identify(trackId, deviceId, type, filename, new byte[] {1, 2});
      audit.expectedSteps(List.of(step));
      try (var stepScope = audit.attach(audit.capture(), step)) {
        audit.stepStarted(step);
        audit.mlResponse(
            "/audit-integrity-fixture",
            200,
            Instant.now(),
            1,
            "{\"auditFixture\":true}".getBytes(StandardCharsets.UTF_8),
            null);
        audit.stepFinished(
            step,
            signals,
            AnalysisQuality.VALID,
            2,
            Map.of("source", "audit-integrity-fixture"),
            null,
            null);
      }
      Track track = persistence.findOrCreateTrack(trackId, deviceId);
      persistence.saveAssessment(track, requestId, step, signals, 2, type, null, presence);
      audit.consolidationAttempt(1);
      var consolidated = persistence.consolidateTrack(trackId, signals, true);
      audit.consolidationCommitted();
      Assessment assessment = consolidated.finalAssessment();
      audit.response(
          EventChunkResponseDto.builder()
              .trackId(trackId.toString())
              .chunkType(type.name())
              .finalVerdict(assessment.getVerdict().name())
              .finalSeverity(assessment.getSeverity().name())
              .message(assessment.getMessage())
              .build());
      assertThat(audit.complete(201, null).validForQuantitativeValidation()).isTrue();
    } finally {
      actualPersistence.setValidationAuditRecorder(recorder);
      evaluator.setValidationAuditRecorder(recorder);
    }
  }

  private Path recordPath(UUID trackId, UUID requestId) {
    return directory.resolve("audit").resolve(trackId.toString()).resolve(requestId + ".json");
  }

  private JsonNode read(UUID trackId, UUID requestId) throws Exception {
    return mapper.readTree(
        Files.readString(recordPath(trackId, requestId), StandardCharsets.UTF_8));
  }
}
