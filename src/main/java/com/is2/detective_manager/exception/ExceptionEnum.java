package com.is2.detective_manager.exception;

import org.springframework.http.HttpStatus;

public enum ExceptionEnum {
  INVALID_DEVICE_ID(
      "Invalid device ID", HttpStatus.BAD_REQUEST, "The device_id header must be a valid UUID."),
  ML_SERVICE_ERROR(
      "ML service error",
      HttpStatus.BAD_GATEWAY,
      "There was an error communicating with the ML analysis service."),
  FIREBASE_UPLOAD_ERROR(
      "Firebase upload error",
      HttpStatus.INTERNAL_SERVER_ERROR,
      "There was an error uploading the image to Firebase Storage."),
  NOTIFICATION_ERROR(
      "Notification error",
      HttpStatus.BAD_GATEWAY,
      "There was an error sending the intruder notification."),
  EVENT_NOT_FOUND("Event not found.", HttpStatus.NOT_FOUND, "The requested event does not exist.");

  private final String title;
  private final HttpStatus status;
  private final String detail;

  ExceptionEnum(String title, HttpStatus status, String detail) {
    this.title = title;
    this.status = status;
    this.detail = detail;
  }

  public String getTitle() {
    return title;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getDetail() {
    return detail;
  }
}
