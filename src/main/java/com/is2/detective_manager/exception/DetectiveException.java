package com.is2.detective_manager.exception;

import org.springframework.http.HttpStatus;

public class DetectiveException extends RuntimeException {

  private final String title;
  private final String detail;
  private final HttpStatus httpStatus;

  public DetectiveException(ExceptionEnum exceptionEnum) {
    super(exceptionEnum.getDetail());
    this.title = exceptionEnum.getTitle();
    this.detail = exceptionEnum.getDetail();
    this.httpStatus = exceptionEnum.getStatus();
  }

  public DetectiveException(ExceptionEnum exceptionEnum, String detail) {
    super(detail);
    this.title = exceptionEnum.getTitle();
    this.detail = detail;
    this.httpStatus = exceptionEnum.getStatus();
  }

  public String getTitle() {
    return title;
  }

  public String getDetail() {
    return detail;
  }

  public HttpStatus getHttpStatus() {
    return httpStatus;
  }
}
