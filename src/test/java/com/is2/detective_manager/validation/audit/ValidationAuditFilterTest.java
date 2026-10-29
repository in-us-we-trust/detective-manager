package com.is2.detective_manager.validation.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.AssessmentStatus;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Exercises HTTP audit delivery with recorder data fixtures, without invoking any ML client. */
class ValidationAuditFilterTest {

  private static final String BODY = "{\"data\":{\"message\":\"accepted\"}}";
  private static final String STEP = "ConversationAnalysisStep";
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @TempDir Path temporaryDirectory;

  @Test
  void createdResponseKeepsItsBodyAndExposesServerRequestIdSharedWithAudit() throws Exception {
    Path auditDirectory = temporaryDirectory.resolve("audit");
    ValidationAuditRecorder recorder = recorder(auditDirectory);
    ValidationAuditFilter filter = new ValidationAuditFilter(recorder);
    MockHttpServletRequest request = eventRequest();
    String untrustedRequestId = UUID.randomUUID().toString();
    request.addHeader(ValidationAuditFilter.REQUEST_ID_HEADER, untrustedRequestId);
    MockHttpServletResponse response = new MockHttpServletResponse();
    UUID trackId = UUID.randomUUID();
    AtomicReference<UUID> observedRequestId = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (incoming, outgoing) -> {
          observedRequestId.set(recorder.currentRequestId());
          recordCompleteAuditFixture(recorder, trackId);
          sendCreated((HttpServletResponse) outgoing);
        });

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentAsString()).isEqualTo(BODY);
    assertThat(response.getHeader(ValidationAuditFilter.REQUEST_ID_HEADER))
        .isEqualTo(observedRequestId.get().toString())
        .isNotEqualTo(untrustedRequestId);
    assertThat(response.getHeader(ValidationAuditFilter.AUDIT_STATUS_HEADER)).isEqualTo("RECORDED");
    assertThat(response.getHeader(ValidationAuditFilter.RUN_VALID_HEADER)).isEqualTo("true");
    JsonNode document = readAudit(auditDirectory, trackId, observedRequestId.get());
    assertThat(document.path("requestId").asText()).isEqualTo(observedRequestId.get().toString());
    assertThat(document.path("trackId").asText()).isEqualTo(trackId.toString());
    assertThat(document.path("httpStatus").asInt()).isEqualTo(201);
    assertThat(document.path("analysisStatus").asText()).isEqualTo("COMPLETE");
    assertThat(document.path("validForQuantitativeValidation").asBoolean()).isTrue();
    assertThat(recorder.currentRequestId()).isNull();
  }

  @Test
  void failedStepRemainsExplicitEvenWhenTheHttpResponseIsCreated() throws Exception {
    Path auditDirectory = temporaryDirectory.resolve("audit");
    ValidationAuditRecorder recorder = recorder(auditDirectory);
    ValidationAuditFilter filter = new ValidationAuditFilter(recorder);
    MockHttpServletResponse response = new MockHttpServletResponse();
    UUID trackId = UUID.randomUUID();

    filter.doFilter(
        eventRequest(),
        response,
        (incoming, outgoing) -> {
          recorder.identify(
              trackId, UUID.randomUUID(), ChunkType.AUDIO, "audio.wav", new byte[] {1});
          recorder.expectedSteps(List.of(STEP));
          recorder.stepStarted(STEP);
          recorder.stepFinished(
              STEP,
              List.of(),
              AnalysisQuality.FAILED,
              12,
              Map.of(),
              "No se pudo completar el análisis de audio",
              new IllegalStateException("Incomplete analysis"));
          sendCreated((HttpServletResponse) outgoing);
        });

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentAsString()).isEqualTo(BODY);
    assertThat(response.getHeader(ValidationAuditFilter.AUDIT_STATUS_HEADER)).isEqualTo("RECORDED");
    assertThat(response.getHeader(ValidationAuditFilter.RUN_VALID_HEADER)).isEqualTo("false");
    UUID requestId = UUID.fromString(response.getHeader(ValidationAuditFilter.REQUEST_ID_HEADER));
    JsonNode document = readAudit(auditDirectory, trackId, requestId);
    assertThat(document.path("analysisStatus").asText()).isEqualTo("FAILED");
    assertThat(document.path("steps").get(0).path("quality").asText()).isEqualTo("FAILED");
    assertThat(document.path("steps").get(0).path("error").path("type").asText())
        .isEqualTo(IllegalStateException.class.getName());
    assertThat(document.path("validForQuantitativeValidation").asBoolean()).isFalse();
    assertThat(recorder.currentRequestId()).isNull();
  }

  @Test
  void auditWriteFailureInvalidatesRunWithoutChangingTheHttpResult() throws Exception {
    Path blockedDirectory = temporaryDirectory.resolve("not-a-directory");
    Files.writeString(blockedDirectory, "existing file");
    ValidationAuditRecorder recorder = recorder(blockedDirectory);
    ValidationAuditFilter filter = new ValidationAuditFilter(recorder);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(
        eventRequest(),
        response,
        (incoming, outgoing) -> {
          recorder.identify(
              UUID.randomUUID(), UUID.randomUUID(), ChunkType.IMAGE, "image.png", new byte[] {1});
          sendCreated((HttpServletResponse) outgoing);
        });

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(response.getContentAsString()).isEqualTo(BODY);
    assertThat(response.getHeader(ValidationAuditFilter.AUDIT_STATUS_HEADER)).isEqualTo("FAILED");
    assertThat(response.getHeader(ValidationAuditFilter.RUN_VALID_HEADER)).isEqualTo("false");
    assertThat(Files.readString(blockedDirectory)).isEqualTo("existing file");
    assertThat(recorder.currentRequestId()).isNull();
  }

  @Test
  void unrelatedRoutesAndMethodsHaveNoAuditHeadersOrWrites() throws Exception {
    Path auditDirectory = temporaryDirectory.resolve("audit");
    ValidationAuditRecorder recorder = recorder(auditDirectory);
    ValidationAuditFilter filter = new ValidationAuditFilter(recorder);
    for (MockHttpServletRequest request :
        List.of(
            new MockHttpServletRequest("GET", ValidationAuditFilter.EVENTS_PATH),
            new MockHttpServletRequest("POST", "/api/detective-manager/v1/devices"),
            new MockHttpServletRequest("POST", ValidationAuditFilter.EVENTS_PATH + "/"))) {
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(
          request,
          response,
          (incoming, outgoing) -> {
            assertThat(recorder.currentRequestId()).isNull();
            sendCreated((HttpServletResponse) outgoing);
          });
      assertThat(response.getContentAsString()).isEqualTo(BODY);
      assertThat(response.getHeader(ValidationAuditFilter.REQUEST_ID_HEADER)).isNull();
      assertThat(response.getHeader(ValidationAuditFilter.AUDIT_STATUS_HEADER)).isNull();
      assertThat(response.getHeader(ValidationAuditFilter.RUN_VALID_HEADER)).isNull();
    }
    assertThat(Files.exists(auditDirectory)).isFalse();
  }

  @Test
  void chainExceptionIsPreservedAndRecordedBeforeContextIsCleared() throws Exception {
    Path auditDirectory = temporaryDirectory.resolve("audit");
    ValidationAuditRecorder recorder = recorder(auditDirectory);
    ValidationAuditFilter filter = new ValidationAuditFilter(recorder);
    MockHttpServletResponse response = new MockHttpServletResponse();
    UUID trackId = UUID.randomUUID();
    ServletException originalFailure = new ServletException("Request processing failed");

    ServletException observed =
        assertThrows(
            ServletException.class,
            () ->
                filter.doFilter(
                    eventRequest(),
                    response,
                    (incoming, outgoing) -> {
                      recorder.identify(
                          trackId,
                          UUID.randomUUID(),
                          ChunkType.VIDEO,
                          "chunk_001.mp4",
                          new byte[] {1});
                      throw originalFailure;
                    }));

    assertSame(originalFailure, observed);
    assertThat(response.getHeader(ValidationAuditFilter.RUN_VALID_HEADER)).isEqualTo("false");
    UUID requestId = UUID.fromString(response.getHeader(ValidationAuditFilter.REQUEST_ID_HEADER));
    JsonNode document = readAudit(auditDirectory, trackId, requestId);
    assertThat(document.path("httpStatus").asInt()).isEqualTo(500);
    assertThat(document.path("errors").get(0).path("type").asText())
        .isEqualTo(ServletException.class.getName());
    assertThat(recorder.currentRequestId()).isNull();
  }

  @Test
  void responseCopyErrorDoesNotReplaceTheOriginalChainFailure() {
    ValidationAuditRecorder recorder = recorder(temporaryDirectory.resolve("audit"));
    ValidationAuditFilter filter = new ValidationAuditFilter(recorder);
    ServletException originalFailure = new ServletException("Request processing failed");
    IOException copyFailure = new IOException("Client connection closed");
    MockHttpServletResponse response =
        new MockHttpServletResponse() {
          @Override
          public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
              @Override
              public void write(int value) throws IOException {
                throw copyFailure;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setWriteListener(WriteListener listener) {}
            };
          }
        };

    ServletException observed =
        assertThrows(
            ServletException.class,
            () ->
                filter.doFilter(
                    eventRequest(),
                    response,
                    (incoming, outgoing) -> {
                      sendCreated((HttpServletResponse) outgoing);
                      throw originalFailure;
                    }));

    assertSame(originalFailure, observed);
    assertThat(observed.getSuppressed()).containsExactly(copyFailure);
    assertThat(recorder.currentRequestId()).isNull();
  }

  private ValidationAuditRecorder recorder(Path directory) {
    return new ValidationAuditRecorder(mapper, directory.toString());
  }

  private MockHttpServletRequest eventRequest() {
    return new MockHttpServletRequest("POST", ValidationAuditFilter.EVENTS_PATH);
  }

  private JsonNode readAudit(Path directory, UUID trackId, UUID requestId) throws Exception {
    return mapper.readTree(
        directory.resolve(trackId.toString()).resolve(requestId + ".json").toFile());
  }

  private void sendCreated(HttpServletResponse response) throws java.io.IOException {
    response.setStatus(201);
    response.setContentType("application/json");
    response.getWriter().write(BODY);
  }

  private void recordCompleteAuditFixture(ValidationAuditRecorder recorder, UUID trackId) {
    recorder.identify(trackId, UUID.randomUUID(), ChunkType.AUDIO, "audio.wav", new byte[] {1, 2});
    recorder.expectedSteps(List.of(STEP));
    recorder.stepStarted(STEP);
    try (ValidationAuditRecorder.Scope ignored = recorder.attach(recorder.capture(), STEP)) {
      // This is input data for the audit writer, without an ML endpoint or inference substitute.
      recorder.mlResponse(
          "/api/ml-service/v1/analysis/conversation",
          200,
          Instant.now(),
          3,
          "{\"auditFixture\":true}".getBytes(StandardCharsets.UTF_8),
          null);
    }
    recorder.stepFinished(STEP, List.of(), AnalysisQuality.VALID, 5, Map.of(), null, null);
    Assessment assessment =
        Assessment.builder()
            .status(AssessmentStatus.EVALUATING)
            .verdict(Verdict.CONTINUE)
            .severity(Severity.NORMAL)
            .message("Audit fixture assessment")
            .build();
    recorder.consolidationAttempt(1);
    recorder.ruleSelected(Set.of(), null, assessment);
    recorder.consolidationSnapshot(trackId, List.of(), assessment, false);
    recorder.consolidationCommitted();
  }
}
