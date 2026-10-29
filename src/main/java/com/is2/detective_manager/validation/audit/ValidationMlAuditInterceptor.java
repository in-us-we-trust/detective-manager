package com.is2.detective_manager.validation.audit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Observes the existing ML exchange without retaining the request body or making another call. */
public class ValidationMlAuditInterceptor implements ClientHttpRequestInterceptor {

  private final ValidationAuditRecorder recorder;
  private final URI mlBaseUri;
  private final String analysisPathPrefix;

  public ValidationMlAuditInterceptor(ValidationAuditRecorder recorder, String mlBaseUrl) {
    this.recorder = recorder;
    this.mlBaseUri = URI.create(mlBaseUrl);
    String basePath = mlBaseUri.getPath() == null ? "" : mlBaseUri.getPath().replaceAll("/+$", "");
    this.analysisPathPrefix = basePath + "/api/ml-service/v1/analysis/";
  }

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    if (recorder.currentRequestId() == null || !isMlAnalysis(request.getURI())) {
      return execution.execute(request, body);
    }

    String endpoint = request.getURI().getPath();
    Instant startedAt = Instant.now();
    long startedNanos = System.nanoTime();
    ClientHttpResponse response;
    try {
      response = execution.execute(request, body);
    } catch (IOException | RuntimeException exception) {
      recorder.mlResponse(endpoint, 0, startedAt, elapsedMillis(startedNanos), null, exception);
      throw exception;
    }

    int status = 0;
    byte[] responseBody;
    try {
      status = response.getStatusCode().value();
      responseBody = response.getBody().readAllBytes();
    } catch (IOException | RuntimeException exception) {
      recorder.mlResponse(
          endpoint, status, startedAt, elapsedMillis(startedNanos), null, exception);
      response.close();
      throw exception;
    }
    recorder.mlResponse(
        endpoint, status, startedAt, elapsedMillis(startedNanos), responseBody, null);
    return new ReplayableResponse(response, responseBody);
  }

  private boolean isMlAnalysis(URI uri) {
    return sameIgnoreCase(mlBaseUri.getScheme(), uri.getScheme())
        && sameIgnoreCase(mlBaseUri.getHost(), uri.getHost())
        && effectivePort(mlBaseUri) == effectivePort(uri)
        && uri.getPath() != null
        && uri.getPath().startsWith(analysisPathPrefix);
  }

  private static boolean sameIgnoreCase(String first, String second) {
    return first != null && second != null && first.equalsIgnoreCase(second);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) ? 443 : 80;
  }

  private static long elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  /** Each consumer sees exactly the original bytes, including the existing logging interceptor. */
  private static final class ReplayableResponse implements ClientHttpResponse {

    private final ClientHttpResponse original;
    private final byte[] body;

    private ReplayableResponse(ClientHttpResponse original, byte[] body) {
      this.original = original;
      this.body = body;
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
      return original.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
      return original.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
      return original.getHeaders();
    }

    @Override
    public InputStream getBody() {
      return new ByteArrayInputStream(body);
    }

    @Override
    public void close() {
      original.close();
    }
  }
}
