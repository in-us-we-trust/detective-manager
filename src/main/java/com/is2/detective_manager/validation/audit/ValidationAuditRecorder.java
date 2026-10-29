package com.is2.detective_manager.validation.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.Rule;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Observación independiente del pipeline, habilitada exclusivamente durante la validación. */
@Component
@Profile("validacion")
@Slf4j
public class ValidationAuditRecorder {

  private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]{256,}={0,2}");
  private static final Pattern DATA_URI =
      Pattern.compile("data:[^\\s\\\"]*;base64,[A-Za-z0-9+/=]+", Pattern.CASE_INSENSITIVE);
  private static final Pattern SENSITIVE_JSON =
      Pattern.compile(
          "(?i)([\\\"'](?:image(?:_?base64)?|audio(?:_?base64)?|video(?:_?base64)?|payload|base64|input|embeddings?|reference_?embeddings)[\\\"']\\s*:\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'[^']*'|\\[[^\\]]*\\])");
  private static final Set<String> OMITTED_KEYS =
      Set.of(
          "image",
          "audio",
          "video",
          "payload",
          "base64",
          "input",
          "imagebase64",
          "audiobase64",
          "videobase64",
          "embedding",
          "embeddings",
          "referenceembeddings");

  private final ObjectMapper mapper;
  private final Path directory;
  private final ThreadLocal<Context> current = new ThreadLocal<>();

  public ValidationAuditRecorder(
      ObjectMapper mapper,
      @Value("${validation.audit.directory:./validation-data/audit}") String directory) {
    this.mapper = mapper;
    this.directory = Path.of(directory).toAbsolutePath().normalize();
  }

  /** Un contexto se captura explícitamente: los pools no heredan ThreadLocal. */
  public static final class Context {
    private final Session session;
    private final String step;

    private Context(Session session, String step) {
      this.session = session;
      this.step = step;
    }
  }

  public final class Scope implements AutoCloseable {
    private final Context previous;
    private boolean closed;

    private Scope(Context context) {
      previous = current.get();
      if (context == null) {
        current.remove();
      } else {
        current.set(context);
      }
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (previous == null) {
        current.remove();
      } else {
        current.set(previous);
      }
    }
  }

  public record Completion(String auditStatus, boolean validForQuantitativeValidation) {}

  private static final class Session {
    final UUID requestId;
    final Instant receivedAt = Instant.now();
    final Map<String, Instant> stepStarts = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<Map<String, Object>> steps = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Map<String, Object>> exchanges = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Map<String, Object>> attempts = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Map<String, Object>> errors = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Map<String, Object>> auditErrors = new ConcurrentLinkedQueue<>();
    final AtomicInteger exchangeSequence = new AtomicInteger();
    volatile UUID trackId;
    volatile UUID deviceId;
    volatile ChunkType evidenceType;
    volatile String filename;
    volatile String sha256;
    volatile int sizeBytes;
    volatile List<String> expectedSteps = List.of();
    volatile Map<String, Object> response;
    volatile Map<String, Object> consolidation;
    volatile Map<String, Object> pendingRule;
    volatile Map<String, Object> pendingSnapshot;
    volatile int attempt;
    volatile boolean invalidRawResponse;
    volatile Completion completion;

    Session(UUID requestId) {
      this.requestId = requestId;
    }
  }

  public Scope open(UUID requestId) {
    return new Scope(new Context(new Session(requestId), null));
  }

  public UUID currentRequestId() {
    Context context = current.get();
    return context == null ? null : context.session.requestId;
  }

  public Context capture() {
    return current.get();
  }

  public Scope attach(Context context, String stepName) {
    return new Scope(context == null ? null : new Context(context.session, stepName));
  }

  private void observe(Consumer<Session> operation) {
    Context context = current.get();
    if (context == null) {
      return;
    }
    try {
      operation.accept(context.session);
    } catch (Exception exception) {
      auditFailure(context.session, "CAPTURE_FAILED", exception);
    }
  }

  public void identify(
      UUID trackId, UUID deviceId, ChunkType type, String filename, byte[] payload) {
    observe(
        session -> {
          session.trackId = trackId;
          session.deviceId = deviceId;
          session.evidenceType = type;
          session.filename = safeText(filename);
          session.sha256 = sha256(payload == null ? new byte[0] : payload);
          session.sizeBytes = payload == null ? 0 : payload.length;
        });
  }

  public void expectedSteps(List<String> steps) {
    observe(session -> session.expectedSteps = List.copyOf(steps));
  }

  public void stepStarted(String step) {
    observe(session -> session.stepStarts.put(step, Instant.now()));
  }

  public void stepFinished(
      String step,
      List<Signal> signals,
      AnalysisQuality quality,
      long durationMs,
      Map<String, Object> data,
      String message,
      Throwable error) {
    observe(
        session -> {
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("step", step);
          result.put("requestId", session.requestId.toString());
          result.put("startedAt", text(session.stepStarts.get(step)));
          result.put("completedAt", Instant.now().toString());
          result.put("durationMs", durationMs);
          result.put("quality", quality == null ? "FAILED" : quality.name());
          result.put("signals", signals == null ? List.of() : List.copyOf(signals));
          result.put(
              "data",
              sanitize(
                  mapper.valueToTree(data == null ? Map.of() : data), new ArrayList<>(), "data"));
          result.put("message", safeText(message));
          result.put("error", error == null ? null : errorDetails(error));
          session.steps.add(result);
        });
  }

  public void ruleSelected(Set<Signal> effectiveSignals, Rule winner, Assessment assessment) {
    observe(
        session -> {
          Map<String, Object> rule = new LinkedHashMap<>();
          rule.put("default", winner == null);
          rule.put(
              "requires",
              winner == null
                  ? List.of()
                  : winner.getRequires().stream().map(Enum::name).sorted().toList());
          rule.put("messagePriority", winner == null ? null : winner.getMessagePriority());
          rule.put("message", winner == null ? assessment.getMessage() : winner.getMessage());
          rule.put("verdict", assessment.getVerdict());
          rule.put("severity", assessment.getSeverity());
          rule.put(
              "id",
              winner == null
                  ? "DEFAULT"
                  : sha256(mapper.valueToTree(rule).toString().getBytes(StandardCharsets.UTF_8)));
          // La identidad de la regla depende de su definición, no de las señales de esta visita.
          rule.put("effectiveSignals", effectiveSignals.stream().map(Enum::name).sorted().toList());
          session.pendingRule = rule;
        });
  }

  public void consolidationAttempt(int attempt) {
    observe(
        session -> {
          session.attempt = attempt;
          session.pendingRule = null;
          session.pendingSnapshot = null;
        });
  }

  public void consolidationSnapshot(
      UUID trackId, List<Signal> signals, Assessment assessment, boolean shouldNotify) {
    observe(
        session -> {
          Map<String, Object> snapshot = new LinkedHashMap<>();
          snapshot.put("trackId", trackId.toString());
          snapshot.put("evaluatedAt", Instant.now().toString());
          snapshot.put("accumulatedSignals", signals.stream().map(Enum::name).distinct().toList());
          snapshot.put("winningRule", session.pendingRule);
          snapshot.put("verdict", assessment.getVerdict());
          snapshot.put("severity", assessment.getSeverity());
          snapshot.put("message", assessment.getMessage());
          snapshot.put("assessmentStatus", assessment.getStatus());
          snapshot.put("shouldNotify", shouldNotify);
          snapshot.put("attempt", session.attempt);
          session.pendingSnapshot = snapshot;
        });
  }

  public void consolidationCommitted() {
    observe(
        session -> {
          session.consolidation = session.pendingSnapshot;
          Map<String, Object> attempt = new LinkedHashMap<>();
          attempt.put("attempt", session.attempt);
          attempt.put("status", "COMMITTED");
          attempt.put("completedAt", Instant.now().toString());
          session.attempts.add(attempt);
          session.pendingSnapshot = null;
          session.pendingRule = null;
        });
  }

  public void consolidationRolledBack(Throwable error) {
    observe(
        session -> {
          Map<String, Object> attempt = new LinkedHashMap<>();
          attempt.put("attempt", session.attempt);
          attempt.put("status", "ROLLED_BACK");
          attempt.put("completedAt", Instant.now().toString());
          attempt.put("error", errorDetails(error));
          session.attempts.add(attempt);
          session.pendingSnapshot = null;
          session.pendingRule = null;
        });
  }

  public void response(EventChunkResponseDto response) {
    observe(session -> session.response = mapper.convertValue(response, Map.class));
  }

  public void mlResponse(
      String endpoint,
      int httpStatus,
      Instant startedAt,
      long durationMs,
      byte[] body,
      Throwable transportError) {
    Context context = current.get();
    if (context == null) {
      return;
    }
    observe(
        session -> {
          int sequence = session.exchangeSequence.incrementAndGet();
          Map<String, Object> exchange = new LinkedHashMap<>();
          exchange.put("sequence", sequence);
          exchange.put("requestId", session.requestId.toString());
          exchange.put("step", context.step == null ? "auxiliary" : context.step);
          exchange.put("endpoint", endpoint);
          exchange.put("httpStatus", httpStatus < 0 ? null : httpStatus);
          exchange.put("startedAt", text(startedAt));
          exchange.put("completedAt", Instant.now().toString());
          exchange.put("durationMs", durationMs);
          exchange.put("error", transportError == null ? null : errorDetails(transportError));
          byte[] received = body == null ? new byte[0] : body;
          exchange.put("originalByteLength", received.length);
          exchange.put("originalSha256", sha256(received));
          if (endpoint.contains("face-embedding")) {
            exchange.put("bodyRedactedReason", "EMBEDDING_VECTORS_NOT_DUPLICATED");
          }
          if (httpStatus >= 400 || transportError != null) {
            Map<String, Object> error =
                transportError == null ? new LinkedHashMap<>() : errorDetails(transportError);
            if (transportError == null) {
              error.put("type", "ML_HTTP_ERROR");
              error.put("message", "ML devolvió una respuesta HTTP de error");
            }
            error.put("httpStatus", httpStatus < 0 ? null : httpStatus);
            error.put("endpoint", endpoint);
            error.put("step", context.step == null ? "auxiliary" : context.step);
            session.errors.add(error);
          }
          if (body != null) {
            List<String> redactions = new ArrayList<>();
            JsonNode original;
            boolean validJson = true;
            String wireText = new String(received, StandardCharsets.UTF_8);
            try {
              original = mapper.readTree(received);
              if (original == null) {
                throw new IOException("Empty ML response");
              }
            } catch (IOException parseError) {
              validJson = false;
              session.invalidRawResponse = true;
              ObjectNode invalid = mapper.createObjectNode();
              invalid.put("invalidJson", true);
              invalid.put("bodyText", safeRawText(wireText));
              original = invalid;
            }
            JsonNode sanitized = sanitize(original, redactions, "$");
            // Revisar también el documento de transporte: el parser puede descartar claves
            // duplicadas cuyo primer valor contenga un payload sensible.
            boolean wireRedacted = !safeRawText(wireText).equals(wireText);
            if (wireRedacted && redactions.isEmpty()) {
              redactions.add("$ (wire content)");
            }
            exchange.put("redactedFields", redactions);
            if (validJson && redactions.isEmpty()) {
              exchange.put("bodyRepresentation", "ORIGINAL_BYTES");
              exchange.put("_bodyBytes", received.clone());
            } else {
              exchange.put(
                  "bodyRepresentation", validJson ? "SANITIZED_JSON" : "INVALID_JSON_TEXT");
              exchange.put("_body", sanitized);
            }
          }
          session.exchanges.add(exchange);
        });
  }

  /**
   * Devuelve FAILED ante cualquier fallo de captura/escritura sin alterar el resultado funcional.
   */
  public Completion complete(int httpStatus, Throwable failure) {
    Context context = current.get();
    if (context == null) {
      return new Completion("FAILED", false);
    }
    Session session = context.session;
    synchronized (session) {
      if (session.completion != null) {
        return session.completion;
      }
      if (failure != null) {
        session.errors.add(errorDetails(failure));
      }
      String analysisStatus = analysisStatus(session);
      boolean valid =
          failure == null
              && httpStatus >= 200
              && httpStatus < 300
              && "COMPLETE".equals(analysisStatus)
              && session.auditErrors.isEmpty();
      Path trackDirectory =
          directory.resolve(session.trackId == null ? "_unresolved" : session.trackId.toString());
      Map<String, Object> document = new LinkedHashMap<>();
      document.put("schemaVersion", 1);
      document.put("requestId", session.requestId.toString());
      document.put("trackId", text(session.trackId));
      document.put("deviceId", text(session.deviceId));
      document.put("evidenceType", session.evidenceType);
      document.put("filename", session.filename);
      document.put("sha256", session.sha256);
      document.put("sizeBytes", session.sizeBytes);
      document.put("receivedAt", session.receivedAt.toString());
      document.put("completedAt", Instant.now().toString());
      document.put("httpStatus", httpStatus);
      document.put("analysisStatus", analysisStatus);
      document.put("expectedSteps", session.expectedSteps);
      document.put("steps", List.copyOf(session.steps));
      document.put("consolidation", session.consolidation);
      document.put("consolidationAttempts", List.copyOf(session.attempts));
      document.put("response", session.response);
      document.put("errors", List.copyOf(session.errors));
      List<Map<String, Object>> exchanges = new ArrayList<>();
      for (Map<String, Object> original : session.exchanges) {
        Map<String, Object> exchange = new LinkedHashMap<>(original);
        JsonNode raw = (JsonNode) exchange.remove("_body");
        byte[] rawBytes = (byte[]) exchange.remove("_bodyBytes");
        if (raw != null || rawBytes != null) {
          String relative =
              session.requestId
                  + "/ml-"
                  + String.format("%03d", exchange.get("sequence"))
                  + ".json.gz";
          try {
            byte[] json = rawBytes == null ? mapper.writeValueAsBytes(raw) : rawBytes;
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
              gzip.write(json);
            }
            byte[] gzip = compressed.toByteArray();
            atomicWrite(trackDirectory.resolve(relative), gzip);
            exchange.put("path", relative);
            exchange.put("encoding", "gzip");
            exchange.put("storedSha256", sha256(gzip));
            exchange.put("storedByteLength", gzip.length);
          } catch (Exception exception) {
            auditFailure(session, "RAW_RESPONSE_WRITE_FAILED", exception);
            exchange.put("captureStatus", "FAILED");
          }
        }
        exchanges.add(exchange);
      }
      document.put("mlResponses", exchanges);
      String auditStatus = session.auditErrors.isEmpty() ? "RECORDED" : "FAILED";
      valid = valid && "RECORDED".equals(auditStatus);
      document.put("auditStatus", auditStatus);
      document.put("validForQuantitativeValidation", valid);
      document.put("auditErrors", List.copyOf(session.auditErrors));
      try {
        atomicWrite(
            trackDirectory.resolve(session.requestId + ".json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
      } catch (Exception exception) {
        auditFailure(session, "MAIN_RECORD_WRITE_FAILED", exception);
        auditStatus = "FAILED";
        valid = false;
      }
      session.completion = new Completion(auditStatus, valid);
      if (!valid) {
        log.warn(
            "[VALIDATION AUDIT INVALID] requestId={} trackId={} auditStatus={} analysisStatus={} httpStatus={}",
            session.requestId,
            session.trackId,
            auditStatus,
            analysisStatus,
            httpStatus);
      }
      return session.completion;
    }
  }

  /** COMPLETE exige captura ML por step y una evaluación con regla publicada tras el commit. */
  private String analysisStatus(Session session) {
    long valid = session.steps.stream().filter(step -> "VALID".equals(step.get("quality"))).count();
    boolean allExpected =
        !session.expectedSteps.isEmpty()
            && session.steps.stream()
                .map(step -> step.get("step"))
                .toList()
                .containsAll(session.expectedSteps)
            && session.steps.size() == session.expectedSteps.size();
    boolean responsesCaptured =
        session.expectedSteps.stream()
            .allMatch(
                step ->
                    session.exchanges.stream()
                        .anyMatch(
                            exchange ->
                                step.equals(exchange.get("step"))
                                    && (exchange.get("_body") != null
                                        || exchange.get("_bodyBytes") != null)));
    boolean mlFailures =
        session.exchanges.stream()
            .anyMatch(
                exchange ->
                    exchange.get("error") != null
                        || !(exchange.get("httpStatus") instanceof Integer status)
                        || status < 200
                        || status >= 300);
    if (allExpected
        && valid == session.steps.size()
        && session.consolidation != null
        && session.consolidation.get("winningRule") != null
        && responsesCaptured
        && !session.invalidRawResponse
        && !mlFailures) {
      return "COMPLETE";
    }
    boolean insufficient =
        session.steps.stream().anyMatch(step -> "INSUFFICIENT".equals(step.get("quality")));
    return valid == 0 && !insufficient ? "FAILED" : "PARTIAL";
  }

  private void auditFailure(Session session, String code, Exception exception) {
    Map<String, Object> error = errorDetails(exception);
    error.put("code", code);
    session.auditErrors.add(error);
    log.error(
        "[VALIDATION AUDIT FAILED] requestId={} trackId={} code={} executionInvalid=true errorType={}",
        session.requestId,
        session.trackId,
        code,
        exception.getClass().getName());
  }

  private JsonNode sanitize(JsonNode node, List<String> redactions, String path) {
    if (node.isObject()) {
      ObjectNode output = mapper.createObjectNode();
      node.fields()
          .forEachRemaining(
              entry -> {
                String key =
                    entry
                        .getKey()
                        .replace("_", "")
                        .replace("-", "")
                        .toLowerCase(java.util.Locale.ROOT);
                String childPath = path + "." + entry.getKey();
                if (OMITTED_KEYS.contains(key)) {
                  output.put(entry.getKey(), "[OMITTED: MEDIA_OR_EMBEDDING]");
                  redactions.add(childPath);
                } else {
                  output.set(entry.getKey(), sanitize(entry.getValue(), redactions, childPath));
                }
              });
      return output;
    }
    if (node.isArray()) {
      ArrayNode output = mapper.createArrayNode();
      for (int index = 0; index < node.size(); index++) {
        output.add(sanitize(node.get(index), redactions, path + "[" + index + "]"));
      }
      return output;
    }
    if (node.isTextual()) {
      String sanitized = safeRawText(node.textValue());
      if (!sanitized.equals(node.textValue())) {
        redactions.add(path);
      }
      return mapper.getNodeFactory().textNode(sanitized);
    }
    return node;
  }

  private static Map<String, Object> errorDetails(Throwable error) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", error.getClass().getName());
    result.put("message", safeText(error.getMessage()));
    List<Map<String, Object>> causes = new ArrayList<>();
    Throwable cause = error.getCause();
    for (int index = 0;
        cause != null && cause != error && index < 5;
        index++, cause = cause.getCause()) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("type", cause.getClass().getName());
      details.put("message", safeText(cause.getMessage()));
      causes.add(details);
    }
    result.put("causes", causes);
    return result;
  }

  private static String safeRawText(String text) {
    if (text == null) {
      return null;
    }
    String structured =
        SENSITIVE_JSON.matcher(text).replaceAll("$1\"[OMITTED: MEDIA_OR_EMBEDDING]\"");
    return BASE64
        .matcher(DATA_URI.matcher(structured).replaceAll("[OMITTED: BASE64]"))
        .replaceAll("[OMITTED: BASE64]");
  }

  private static String safeText(String text) {
    // Excepciones HTTP pueden representar sus cuerpos JSON con comillas escapadas.
    String sanitized = safeRawText(text == null ? null : text.replace("\\\"", "\""));
    return sanitized == null || sanitized.length() <= 8192
        ? sanitized
        : sanitized.substring(0, 8192) + " [TRUNCATED]";
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 no disponible", exception);
    }
  }

  private static void atomicWrite(Path destination, byte[] bytes) throws IOException {
    Files.createDirectories(destination.getParent());
    Path temporary = Files.createTempFile(destination.getParent(), ".audit-", ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
