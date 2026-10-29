package com.is2.detective_manager.validation.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.RuleBasedDangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Verifies audit data and persistence; no model or HTTP inference is executed or simulated. */
class ValidationAuditRecorderTest {

  @TempDir Path directory;

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @ParameterizedTest
  @EnumSource(ChunkType.class)
  void eachEvidenceKeepsIdentificationStepsAndCompleteCompressedResponse(ChunkType type)
      throws Exception {
    ValidationAuditRecorder recorder = recorder();
    UUID requestId = UUID.randomUUID();
    UUID trackId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    byte[] evidence = {0, 1, 2, -1};
    String filename = type == ChunkType.VIDEO ? "chunk_004.mp4" : "reference-" + type;
    String raw = rawFixture(type);
    String endpoint = endpoint(type);
    Signal signal = signal(type);

    try (var request = recorder.open(requestId)) {
      recorder.identify(trackId, deviceId, type, filename, evidence);
      recorder.expectedSteps(List.of("AuditFixtureStep"));
      try (var step = recorder.attach(recorder.capture(), "AuditFixtureStep")) {
        recorder.stepStarted("AuditFixtureStep");
        recorder.mlResponse(endpoint, 200, Instant.now(), 17, bytes(raw), null);
        recorder.stepFinished(
            "AuditFixtureStep",
            List.of(signal),
            AnalysisQuality.VALID,
            19,
            Map.of("derivedCount", 2, "observedSeconds", 5.0),
            null,
            null);
      }
      Assessment assessment = consolidate(recorder, trackId, List.of(signal), 1);
      recorder.response(response(trackId, type, assessment));
      var completed = recorder.complete(201, null);
      assertThat(completed.auditStatus()).isEqualTo("RECORDED");
      assertThat(completed.validForQuantitativeValidation()).isTrue();
    }

    assertThat(recorder.currentRequestId()).isNull();
    JsonNode document = read(trackId, requestId);
    assertThat(document.path("requestId").asText()).isEqualTo(requestId.toString());
    assertThat(document.path("trackId").asText()).isEqualTo(trackId.toString());
    assertThat(document.path("deviceId").asText()).isEqualTo(deviceId.toString());
    assertThat(document.path("evidenceType").asText()).isEqualTo(type.name());
    assertThat(document.path("filename").asText()).isEqualTo(filename);
    assertThat(document.path("sha256").asText()).isEqualTo(hash(evidence));
    assertThat(document.path("sizeBytes").asInt()).isEqualTo(evidence.length);
    assertThat(Instant.parse(document.path("receivedAt").asText()))
        .isBeforeOrEqualTo(Instant.parse(document.path("completedAt").asText()));
    assertThat(document.path("httpStatus").asInt()).isEqualTo(201);
    assertThat(document.path("analysisStatus").asText()).isEqualTo("COMPLETE");
    JsonNode step = document.path("steps").get(0);
    assertThat(step.path("requestId").asText()).isEqualTo(requestId.toString());
    assertThat(step.path("quality").asText()).isEqualTo("VALID");
    assertThat(step.path("durationMs").asInt()).isEqualTo(19);
    assertThat(step.path("data").path("derivedCount").asInt()).isEqualTo(2);
    assertThat(step.path("signals").get(0).asText()).isEqualTo(signal.name());
    assertThat(step.path("startedAt").asText()).isNotBlank();
    assertThat(step.path("completedAt").asText()).isNotBlank();
    JsonNode exchange = document.path("mlResponses").get(0);
    assertThat(exchange.path("step").asText()).isEqualTo("AuditFixtureStep");
    assertThat(exchange.path("endpoint").asText()).isEqualTo(endpoint);
    assertThat(exchange.path("durationMs").asInt()).isEqualTo(17);
    assertThat(exchange.path("originalSha256").asText()).isEqualTo(hash(bytes(raw)));
    assertThat(exchange.path("encoding").asText()).isEqualTo("gzip");
    Path compressed = directory.resolve(trackId.toString()).resolve(exchange.path("path").asText());
    assertThat(exchange.path("storedSha256").asText())
        .isEqualTo(hash(Files.readAllBytes(compressed)));
    assertThat(readCompressedBytes(compressed)).isEqualTo(bytes(raw));
    assertThat(readCompressed(compressed)).isEqualTo(mapper.readTree(raw));
    assertThat(exchange.has("_body")).isFalse();
    try (var files = Files.walk(directory)) {
      assertThat(
              files
                  .filter(Files::isRegularFile)
                  .map(path -> path.getFileName().toString())
                  .toList())
          .hasSize(2)
          .noneMatch(
              name -> name.endsWith(".tmp") || name.endsWith(".mp4") || name.endsWith(".wav"));
    }
  }

  @Test
  void incompleteAndFailedStepsRemainExplicitEvenWithSuccessfulHttpResponse() throws Exception {
    var recorder = recorder();
    UUID requestId = UUID.randomUUID();
    UUID trackId = UUID.randomUUID();
    try (var request = recorder.open(requestId)) {
      recorder.identify(
          trackId, UUID.randomUUID(), ChunkType.IMAGE, "evidence.png", new byte[] {1});
      recorder.expectedSteps(List.of("ValidStep", "IncompleteStep", "FailedStep"));
      recordStep(recorder, "ValidStep", AnalysisQuality.VALID, List.of(Signal.PERSON), null);
      recordStep(recorder, "IncompleteStep", AnalysisQuality.INSUFFICIENT, List.of(), null);
      recordStep(
          recorder,
          "FailedStep",
          AnalysisQuality.FAILED,
          List.of(),
          new IOException("transport reset"));
      recorder.mlResponse(
          "/analysis/object-detection",
          500,
          Instant.now(),
          20,
          bytes("{\"detail\":\"inference failed\",\"diagnostic\":{\"phase\":\"decode\"}}"),
          null);
      consolidate(recorder, trackId, List.of(Signal.PERSON), 1);
      var complete = recorder.complete(201, null);
      assertThat(complete.auditStatus()).isEqualTo("RECORDED");
      assertThat(complete.validForQuantitativeValidation()).isFalse();
    }
    JsonNode document = read(trackId, requestId);
    assertThat(document.path("analysisStatus").asText()).isEqualTo("PARTIAL");
    assertThat(document.path("steps").get(1).path("quality").asText()).isEqualTo("INSUFFICIENT");
    assertThat(document.path("steps").get(2).path("quality").asText()).isEqualTo("FAILED");
    assertThat(document.path("steps").get(2).path("error").path("type").asText())
        .isEqualTo(IOException.class.getName());
    assertThat(document.path("steps").get(2).path("error").path("message").asText())
        .isEqualTo("transport reset");
    assertThat(document.path("mlResponses").get(0).path("httpStatus").asInt()).isEqualTo(500);
  }

  @Test
  void malformedRawJsonAndTransportFailuresArePreservedWithoutSuccessfulAnalysis()
      throws Exception {
    var recorder = recorder();
    UUID requestId = UUID.randomUUID();
    UUID trackId = UUID.randomUUID();
    try (var request = recorder.open(requestId)) {
      recorder.identify(trackId, UUID.randomUUID(), ChunkType.AUDIO, "audio.wav", new byte[] {2});
      recorder.expectedSteps(List.of("ConversationAnalysisStep"));
      recorder.mlResponse(
          "/analysis/conversation",
          502,
          Instant.now(),
          8,
          bytes("<html>upstream unavailable</html>"),
          null);
      recorder.mlResponse(
          "/analysis/conversation",
          -1,
          Instant.now(),
          9,
          null,
          new IOException("connection closed"));
      recordStep(
          recorder,
          "ConversationAnalysisStep",
          AnalysisQuality.FAILED,
          List.of(),
          new IOException("analysis unavailable"));
      consolidate(recorder, trackId, List.of(), 1);
      assertThat(recorder.complete(201, null).validForQuantitativeValidation()).isFalse();
    }
    JsonNode document = read(trackId, requestId);
    assertThat(document.path("analysisStatus").asText()).isEqualTo("FAILED");
    JsonNode first = document.path("mlResponses").get(0);
    JsonNode captured =
        readCompressed(directory.resolve(trackId.toString()).resolve(first.path("path").asText()));
    assertThat(captured.path("invalidJson").asBoolean()).isTrue();
    assertThat(captured.path("bodyText").asText()).isEqualTo("<html>upstream unavailable</html>");
    JsonNode second = document.path("mlResponses").get(1);
    assertThat(second.path("httpStatus").isNull()).isTrue();
    assertThat(second.path("error").path("message").asText()).isEqualTo("connection closed");
  }

  @Test
  void rawAndDerivedDataNeverDuplicateMediaOrReferenceVectors() throws Exception {
    var recorder = recorder();
    UUID requestId = UUID.randomUUID();
    UUID trackId = UUID.randomUUID();
    String base64 = "AQID".repeat(100);
    String raw =
        mapper.writeValueAsString(
            Map.of(
                "image",
                base64,
                "embeddings",
                List.of(Map.of("embedding", List.of(0.125, -0.75))),
                "transcript",
                "texto original conservado",
                "debug",
                "data:image/png;base64," + base64,
                "scores",
                Map.of("THREAT", 0.13)));
    try (var request = recorder.open(requestId)) {
      recorder.identify(trackId, UUID.randomUUID(), ChunkType.AUDIO, "audio.wav", new byte[] {3});
      recorder.expectedSteps(List.of("ConversationAnalysisStep"));
      recorder.stepStarted("ConversationAnalysisStep");
      recorder.stepFinished(
          "ConversationAnalysisStep",
          List.of(Signal.NO_SUSPICIOUS_CONVERSATION),
          AnalysisQuality.VALID,
          4,
          Map.of("payload", base64, "embedding", List.of(0.125, -0.75)),
          null,
          null);
      recorder.mlResponse("/analysis/conversation", 200, Instant.now(), 4, bytes(raw), null);
      recorder.mlResponse(
          "/analysis/face-embedding",
          200,
          Instant.now(),
          4,
          bytes("{\"data\":{\"embedding\":[0.125,-0.75],\"model_name\":\"model\"}}"),
          null);
      consolidate(recorder, trackId, List.of(Signal.NO_SUSPICIOUS_CONVERSATION), 1);
      recorder.complete(201, null);
    }
    JsonNode document = read(trackId, requestId);
    JsonNode first = document.path("mlResponses").get(0);
    JsonNode rawStored =
        readCompressed(directory.resolve(trackId.toString()).resolve(first.path("path").asText()));
    assertThat(rawStored.path("transcript").asText()).isEqualTo("texto original conservado");
    assertThat(rawStored.path("scores").path("THREAT").asDouble()).isEqualTo(0.13);
    assertThat(rawStored.toString()).doesNotContain(base64, "0.125", "-0.75");
    assertThat(document.toString()).doesNotContain(base64, "0.125", "-0.75");
    assertThat(first.path("redactedFields").size()).isGreaterThanOrEqualTo(2);
    JsonNode auxiliary = document.path("mlResponses").get(1);
    assertThat(auxiliary.path("bodyRedactedReason").asText())
        .isEqualTo("EMBEDDING_VECTORS_NOT_DUPLICATED");
    JsonNode auxiliaryBody =
        readCompressed(
            directory.resolve(trackId.toString()).resolve(auxiliary.path("path").asText()));
    assertThat(auxiliaryBody.path("data").path("model_name").asText()).isEqualTo("model");
    assertThat(auxiliaryBody.path("data").path("embedding").asText()).contains("OMITTED");
    assertThat(auxiliaryBody.toString()).doesNotContain("0.125", "-0.75");
  }

  @Test
  void rolledBackAttemptCannotBecomeTheCommittedConsolidation() throws Exception {
    var recorder = recorder();
    UUID requestId = UUID.randomUUID();
    UUID trackId = UUID.randomUUID();
    try (var request = recorder.open(requestId)) {
      recorder.identify(
          trackId, UUID.randomUUID(), ChunkType.IMAGE, "evidence.png", new byte[] {4});
      recorder.expectedSteps(List.of("FaceRecognitionStep"));
      recordStep(
          recorder,
          "FaceRecognitionStep",
          AnalysisQuality.VALID,
          List.of(Signal.UNKNOWN_PERSON),
          null);
      recorder.consolidationAttempt(1);
      var evaluator = evaluator(recorder);
      Assessment rolledBack = evaluator.evaluate(List.of(Signal.WEAPON_DETECTED));
      recorder.consolidationSnapshot(trackId, List.of(Signal.WEAPON_DETECTED), rolledBack, true);
      recorder.consolidationRolledBack(new IllegalStateException("optimistic conflict"));
      consolidate(recorder, trackId, List.of(Signal.UNKNOWN_PERSON), 2);
      recorder.complete(201, null);
    }
    JsonNode document = read(trackId, requestId);
    assertThat(document.path("consolidation").path("accumulatedSignals").toString())
        .contains("UNKNOWN_PERSON")
        .doesNotContain("WEAPON_DETECTED");
    assertThat(document.path("consolidation").path("attempt").asInt()).isEqualTo(2);
    assertThat(document.path("consolidationAttempts").get(0).path("status").asText())
        .isEqualTo("ROLLED_BACK");
    assertThat(document.path("consolidationAttempts").get(1).path("status").asText())
        .isEqualTo("COMMITTED");
  }

  @Test
  void missingCommitAndEarlyRequestFailureCannotLookValid() throws Exception {
    var recorder = recorder();
    UUID requestId = UUID.randomUUID();
    try (var request = recorder.open(requestId)) {
      recorder.expectedSteps(List.of("Step"));
      recordStep(recorder, "Step", AnalysisQuality.VALID, List.of(), null);
      recorder.consolidationAttempt(1);
      Assessment assessment = evaluator(recorder).evaluate(List.of());
      recorder.consolidationSnapshot(UUID.randomUUID(), List.of(), assessment, false);
      assertThat(
              recorder
                  .complete(400, new IllegalArgumentException("invalid event"))
                  .validForQuantitativeValidation())
          .isFalse();
    }
    JsonNode document =
        mapper.readTree(directory.resolve("_unresolved").resolve(requestId + ".json").toFile());
    assertThat(document.path("consolidation").isNull()).isTrue();
    assertThat(document.path("errors").get(0).path("message").asText()).isEqualTo("invalid event");
    assertThat(document.path("validForQuantitativeValidation").asBoolean()).isFalse();
  }

  @Test
  void parallelStepsAndConcurrentRequestsRemainSeparatedWhenThreadsAreReused() throws Exception {
    var recorder = recorder();
    UUID trackId = UUID.randomUUID();
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    var first = prepareParallelRequest(recorder, trackId, firstId);
    var second = prepareParallelRequest(recorder, trackId, secondId);
    var pool = Executors.newFixedThreadPool(4);
    CountDownLatch ready = new CountDownLatch(4);
    CountDownLatch proceed = new CountDownLatch(1);
    try {
      List<Callable<Void>> jobs = new ArrayList<>();
      for (var context : List.of(first, second)) {
        for (String step : List.of("StepOne", "StepTwo")) {
          jobs.add(
              () -> {
                try (var scope = recorder.attach(context, step)) {
                  UUID id = recorder.currentRequestId();
                  ready.countDown();
                  assertThat(proceed.await(5, TimeUnit.SECONDS)).isTrue();
                  recorder.stepStarted(step);
                  recorder.mlResponse(
                      "/audit-fixture",
                      200,
                      Instant.now(),
                      1,
                      bytes("{\"fixtureRequest\":\"" + id + "\",\"step\":\"" + step + "\"}"),
                      null);
                  recorder.stepFinished(
                      step,
                      List.of(Signal.PERSON),
                      AnalysisQuality.VALID,
                      1,
                      Map.of("fixtureRequest", id.toString()),
                      null,
                      null);
                }
                assertThat(recorder.currentRequestId()).isNull();
                return null;
              });
        }
      }
      var futures = jobs.stream().map(pool::submit).toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      proceed.countDown();
      for (var future : futures) {
        future.get(5, TimeUnit.SECONDS);
      }
      for (var context : List.of(first, second)) {
        try (var scope = recorder.attach(context, null)) {
          consolidate(recorder, trackId, List.of(Signal.PERSON), 1);
          assertThat(recorder.complete(201, null).validForQuantitativeValidation()).isTrue();
        }
      }
      for (int index = 0; index < 8; index++) {
        assertThat(pool.submit(recorder::currentRequestId).get(5, TimeUnit.SECONDS)).isNull();
      }
    } finally {
      proceed.countDown();
      pool.shutdownNow();
    }
    assertThat(recorder.currentRequestId()).isNull();
    for (UUID id : List.of(firstId, secondId)) {
      JsonNode document = read(trackId, id);
      assertThat(document.path("steps").size()).isEqualTo(2);
      assertThat(document.path("mlResponses").size()).isEqualTo(2);
      for (JsonNode step : document.path("steps")) {
        assertThat(step.path("requestId").asText()).isEqualTo(id.toString());
        assertThat(step.path("data").path("fixtureRequest").asText()).isEqualTo(id.toString());
      }
      for (JsonNode exchange : document.path("mlResponses")) {
        JsonNode raw =
            readCompressed(
                directory.resolve(trackId.toString()).resolve(exchange.path("path").asText()));
        assertThat(raw.path("fixtureRequest").asText()).isEqualTo(id.toString());
        assertThat(raw.path("step").asText()).isEqualTo(exchange.path("step").asText());
      }
    }
  }

  @Test
  void unwritableDestinationMarksExecutionFailedAndNeverWritesPartialJson() throws Exception {
    Path notDirectory = directory.resolve("not-a-directory");
    Files.writeString(notDirectory, "preserve existing file");
    var recorder = new ValidationAuditRecorder(mapper, notDirectory.toString());
    UUID requestId = UUID.randomUUID();
    UUID trackId = UUID.randomUUID();
    try (var request = recorder.open(requestId)) {
      recorder.identify(
          trackId, UUID.randomUUID(), ChunkType.VIDEO, "chunk_001.mp4", new byte[] {5});
      recorder.expectedSteps(List.of("VideoAnalysisStep"));
      recordStep(
          recorder,
          "VideoAnalysisStep",
          AnalysisQuality.VALID,
          List.of(Signal.NO_SUSPICIOUS_BEHAVIOR),
          null);
      recorder.mlResponse(
          "/analysis/video", 200, Instant.now(), 1, bytes(rawFixture(ChunkType.VIDEO)), null);
      consolidate(recorder, trackId, List.of(Signal.NO_SUSPICIOUS_BEHAVIOR), 1);
      var completion = recorder.complete(201, null);
      assertThat(completion.auditStatus()).isEqualTo("FAILED");
      assertThat(completion.validForQuantitativeValidation()).isFalse();
      assertThat(recorder.complete(201, null)).isEqualTo(completion);
    }
    assertThat(Files.readString(notDirectory)).isEqualTo("preserve existing file");
    try (var files = Files.walk(directory)) {
      assertThat(files.filter(Files::isRegularFile).toList()).containsExactly(notDirectory);
    }
  }

  @Test
  void independentRecorderInstanceKeepsExistingRequestFilesForTheSameTrack() throws Exception {
    UUID trackId = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    writeCompleteRecord(recorder(), trackId, first, Signal.UNKNOWN_PERSON);
    byte[] original = Files.readAllBytes(recordPath(trackId, first));
    writeCompleteRecord(
        new ValidationAuditRecorder(mapper, directory.toString()), trackId, second, Signal.THREAT);
    assertThat(Files.readAllBytes(recordPath(trackId, first))).isEqualTo(original);
    assertThat(read(trackId, first).path("consolidation").path("accumulatedSignals").toString())
        .doesNotContain("THREAT");
    assertThat(read(trackId, second).path("consolidation").path("accumulatedSignals").toString())
        .contains("THREAT");
  }

  @Test
  void winningRuleIdentifierStaysStableWhenUnrelatedSignalsAccumulate() throws Exception {
    var recorder = recorder();
    UUID trackId = UUID.randomUUID();
    List<String> identifiers = new ArrayList<>();
    for (List<Signal> signals :
        List.of(List.of(Signal.THREAT), List.of(Signal.THREAT, Signal.UNKNOWN_PERSON))) {
      UUID requestId = UUID.randomUUID();
      try (var scope = recorder.open(requestId)) {
        recorder.identify(
            trackId, UUID.randomUUID(), ChunkType.AUDIO, "fixture.wav", new byte[] {8});
        recorder.expectedSteps(List.of("FixtureStep"));
        try (var stepScope = recorder.attach(recorder.capture(), "FixtureStep")) {
          recorder.mlResponse(
              "/audit-integrity-fixture",
              200,
              Instant.now(),
              1,
              bytes("{\"auditFixture\":true}"),
              null);
          recordStep(recorder, "FixtureStep", AnalysisQuality.VALID, signals, null);
        }
        consolidate(recorder, trackId, signals, 1);
        assertThat(recorder.complete(201, null).validForQuantitativeValidation()).isTrue();
      }
      identifiers.add(
          read(trackId, requestId).path("consolidation").path("winningRule").path("id").asText());
    }
    assertThat(identifiers.get(0)).isNotBlank().isEqualTo(identifiers.get(1));
  }

  private ValidationAuditRecorder.Context prepareParallelRequest(
      ValidationAuditRecorder recorder, UUID trackId, UUID requestId) {
    try (var scope = recorder.open(requestId)) {
      recorder.identify(
          trackId, UUID.randomUUID(), ChunkType.IMAGE, "parallel.png", new byte[] {6});
      recorder.expectedSteps(List.of("StepOne", "StepTwo"));
      return recorder.capture();
    }
  }

  private void writeCompleteRecord(
      ValidationAuditRecorder recorder, UUID trackId, UUID requestId, Signal signal) {
    try (var scope = recorder.open(requestId)) {
      recorder.identify(
          trackId, UUID.randomUUID(), ChunkType.AUDIO, "evidence.wav", new byte[] {7});
      recorder.expectedSteps(List.of("FixtureStep"));
      try (var stepScope = recorder.attach(recorder.capture(), "FixtureStep")) {
        recorder.mlResponse(
            "/audit-integrity-fixture",
            200,
            Instant.now(),
            1,
            bytes("{\"auditFixture\":true}"),
            null);
        recordStep(recorder, "FixtureStep", AnalysisQuality.VALID, List.of(signal), null);
      }
      consolidate(recorder, trackId, List.of(signal), 1);
      assertThat(recorder.complete(201, null).validForQuantitativeValidation()).isTrue();
    }
  }

  private Assessment consolidate(
      ValidationAuditRecorder recorder, UUID trackId, List<Signal> signals, int attempt) {
    recorder.consolidationAttempt(attempt);
    Assessment assessment = evaluator(recorder).evaluate(signals);
    recorder.consolidationSnapshot(trackId, signals, assessment, false);
    recorder.consolidationCommitted();
    return assessment;
  }

  private RuleBasedDangerEvaluator evaluator(ValidationAuditRecorder recorder) {
    var evaluator = new RuleBasedDangerEvaluator();
    evaluator.setValidationAuditRecorder(recorder);
    return evaluator;
  }

  private void recordStep(
      ValidationAuditRecorder recorder,
      String name,
      AnalysisQuality quality,
      List<Signal> signals,
      Throwable failure) {
    recorder.stepStarted(name);
    recorder.stepFinished(
        name,
        signals,
        quality,
        3,
        Map.of(),
        quality == AnalysisQuality.VALID ? null : "analysis incomplete",
        failure);
  }

  private EventChunkResponseDto response(UUID trackId, ChunkType type, Assessment assessment) {
    return EventChunkResponseDto.builder()
        .trackId(trackId.toString())
        .chunkType(type.name())
        .finalVerdict(assessment.getVerdict().name())
        .finalSeverity(assessment.getSeverity().name())
        .message(assessment.getMessage())
        .build();
  }

  private ValidationAuditRecorder recorder() {
    return new ValidationAuditRecorder(mapper, directory.toString());
  }

  private Path recordPath(UUID trackId, UUID requestId) {
    return directory.resolve(trackId.toString()).resolve(requestId + ".json");
  }

  private JsonNode read(UUID trackId, UUID requestId) throws IOException {
    return mapper.readTree(recordPath(trackId, requestId).toFile());
  }

  private JsonNode readCompressed(Path path) throws IOException {
    try (var gzip = new GZIPInputStream(Files.newInputStream(path))) {
      return mapper.readTree(gzip);
    }
  }

  private byte[] readCompressedBytes(Path path) throws IOException {
    try (var gzip = new GZIPInputStream(Files.newInputStream(path))) {
      return gzip.readAllBytes();
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String hash(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static Signal signal(ChunkType type) {
    return switch (type) {
      case IMAGE -> Signal.UNKNOWN_PERSON;
      case AUDIO -> Signal.AUTHORITY;
      case VIDEO -> Signal.NO_SUSPICIOUS_BEHAVIOR;
    };
  }

  private static String endpoint(ChunkType type) {
    return switch (type) {
      case IMAGE -> "/analysis/face-recognition";
      case AUDIO -> "/analysis/conversation";
      case VIDEO -> "/analysis/video";
    };
  }

  private static String rawFixture(ChunkType type) {
    return switch (type) {
      case IMAGE ->
          """
          {"data":{"known":false,"distance":0.62,"threshold":0.4,"unknownField":{"score":0.123456789012345678901234567890}}}
          """;
      case AUDIO ->
          """
          {"transcript":"Texto de prueba sin inferencia","evidences":[{"type":"AUTHORITY","score":0.89}],"extraScores":{"neutral":0.456}}
          """;
      case VIDEO ->
          """
          {"data":{"metadata":{"fps":5,"duration_s":5},"observations":[{"frame":2,"confidence":0.91,"futureField":[1,2,3]}],"futureMetadata":{"tracker":"audit-fixture"}}}
          """;
    };
  }
}
