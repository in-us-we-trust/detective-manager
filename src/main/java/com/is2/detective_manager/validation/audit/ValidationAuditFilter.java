package com.is2.detective_manager.validation.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Correlates a multimedia request and finishes its audit before sending the JSON response. */
@Component
@Profile("validacion")
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
@Slf4j
public class ValidationAuditFilter extends OncePerRequestFilter {

  public static final String REQUEST_ID_HEADER = "X-Validation-Request-Id";
  public static final String AUDIT_STATUS_HEADER = "X-Validation-Audit-Status";
  public static final String RUN_VALID_HEADER = "X-Validation-Run-Valid";
  public static final String EVENTS_PATH = "/api/detective-manager/v1/orchestrator/events";

  private final ValidationAuditRecorder recorder;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !(request.getContextPath() + EVENTS_PATH).equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    UUID requestId = UUID.randomUUID();
    ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
    cachedResponse.setHeader(REQUEST_ID_HEADER, requestId.toString());

    try (ValidationAuditRecorder.Scope ignored = recorder.open(requestId)) {
      Throwable failure = null;
      try {
        filterChain.doFilter(request, cachedResponse);
      } catch (IOException | ServletException | RuntimeException | Error exception) {
        failure = exception;
        throw exception;
      } finally {
        int effectiveStatus = cachedResponse.getStatus();
        if (failure != null && effectiveStatus < 400) {
          effectiveStatus = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        try {
          ValidationAuditRecorder.Completion completion =
              recorder.complete(effectiveStatus, failure);
          cachedResponse.setHeader(AUDIT_STATUS_HEADER, completion.auditStatus());
          cachedResponse.setHeader(
              RUN_VALID_HEADER, Boolean.toString(completion.validForQuantitativeValidation()));
        } catch (RuntimeException auditFailure) {
          // A recorder failure must never replace the application result or its exception.
          log.error("[VALIDACION AUDIT FAILED] requestId={}", requestId, auditFailure);
          cachedResponse.setHeader(AUDIT_STATUS_HEADER, "FAILED");
          cachedResponse.setHeader(RUN_VALID_HEADER, "false");
        }
        try {
          cachedResponse.copyBodyToResponse();
        } catch (IOException copyFailure) {
          if (failure == null) {
            throw copyFailure;
          }
          if (copyFailure != failure) {
            failure.addSuppressed(copyFailure);
          }
          log.warn(
              "[VALIDACION AUDIT] requestId={} response copy failed after request failure",
              requestId,
              copyFailure);
        }
      }
    }
  }
}
