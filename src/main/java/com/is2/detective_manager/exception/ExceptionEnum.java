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
  DEVICE_NOT_FOUND(
      "Device not found.", HttpStatus.NOT_FOUND, "The requested device does not exist."),
  TRACK_NOT_FOUND("Track not found.", HttpStatus.NOT_FOUND, "The requested track does not exist."),
  DEVICE_ALREADY_EXISTS(
      "Device already exists.",
      HttpStatus.CONFLICT,
      "A device with this ID is already registered."),
  INVALID_LAST_SEEN(
      "Invalid timestamps.",
      HttpStatus.BAD_REQUEST,
      "lastSeen must be greater than or equal to lastBoot."),
  DEVICE_NOT_CLAIMABLE(
      "Device cannot be claimed.",
      HttpStatus.CONFLICT,
      "The device must be in UNCLAIMED state to be claimed."),
  DEVICE_NOT_UNCLAIMABLE(
      "Device cannot be unclaimed.",
      HttpStatus.CONFLICT,
      "The device must be in CLAIMED state to be unclaimed."),
  PERSON_NOT_FOUND(
      "Person not found.", HttpStatus.NOT_FOUND, "The requested person does not exist."),
  IMAGE_NOT_FOUND("Image not found", HttpStatus.NOT_FOUND, "The requested image does not exist."),
  NOT_ALLOWED_IMAGE_ACTION(
      "Invalid access",
      HttpStatus.FORBIDDEN,
      "You do not have permission to access this resource.");

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
