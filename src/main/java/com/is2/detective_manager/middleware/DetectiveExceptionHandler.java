package com.is2.detective_manager.middleware;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionContent;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.is2.detective_manager.controller")
@Slf4j
public class DetectiveExceptionHandler {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private void logException(ExceptionContent exceptionContent) {
    try {
      log.error("Exception occurred: {}", objectMapper.writeValueAsString(exceptionContent));
    } catch (JsonProcessingException e) {
      log.error("Error serializing exception content", e);
    }
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ExceptionContent> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

    String title = "Bad Request";
    String detail = ex.getMessage();

    if (UUID.class.equals(ex.getRequiredType())) {
      title = "Invalid UUID";
      detail = "Invalid UUID format";
    }

    ExceptionContent exceptionContent =
        ExceptionContent.builder()
            .title(title)
            .instance(request.getRequestURI())
            .status(HttpStatus.BAD_REQUEST.value())
            .detail(detail)
            .type("about:blank")
            .build();

    logException(exceptionContent);

    return new ResponseEntity<>(exceptionContent, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(DetectiveException.class)
  public ResponseEntity<ExceptionContent> handleDetectiveException(
      DetectiveException ex, HttpServletRequest request) {
    ExceptionContent exceptionContent =
        ExceptionContent.builder()
            .title(ex.getTitle())
            .instance(request.getRequestURI())
            .status(ex.getHttpStatus().value())
            .detail(ex.getDetail())
            .type("about:blank")
            .build();
    logException(exceptionContent);
    return new ResponseEntity<>(exceptionContent, ex.getHttpStatus());
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ExceptionContent> handleMissingRequestHeader(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    ExceptionContent exceptionContent =
        ExceptionContent.builder()
            .title("Bad Request")
            .instance(request.getRequestURI())
            .status(HttpStatus.BAD_REQUEST.value())
            .detail("Required header '" + ex.getHeaderName() + "' is missing")
            .type("about:blank")
            .build();
    logException(exceptionContent);
    return new ResponseEntity<>(exceptionContent, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ExceptionContent> handleValidationExceptions(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    StringBuilder details = new StringBuilder();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      details.append(error.getField()).append(": ").append(error.getDefaultMessage()).append("; ");
    }

    ExceptionContent exceptionContent =
        ExceptionContent.builder()
            .title("Bad Request")
            .instance(request.getRequestURI())
            .status(HttpStatus.BAD_REQUEST.value())
            .detail(details.toString())
            .type("about:blank")
            .build();
    logException(exceptionContent);
    return new ResponseEntity<>(exceptionContent, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ExceptionContent> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    String detail = ex.getMessage();
    if (ex.getCause() instanceof UnrecognizedPropertyException upex) {
      detail = "Unknown field: " + upex.getPropertyName();
    }

    ExceptionContent exceptionContent =
        ExceptionContent.builder()
            .title("Bad Request")
            .instance(request.getRequestURI())
            .status(HttpStatus.BAD_REQUEST.value())
            .detail(detail)
            .type("about:blank")
            .build();
    logException(exceptionContent);
    return new ResponseEntity<>(exceptionContent, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ExceptionContent> handleAllExceptions(
      Exception ex, HttpServletRequest request) {
    ExceptionContent exceptionContent =
        ExceptionContent.builder()
            .title("Internal Server Error")
            .instance(request.getRequestURI())
            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .detail(ex.getMessage())
            .type("about:blank")
            .build();
    logException(exceptionContent);
    return new ResponseEntity<>(exceptionContent, HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
