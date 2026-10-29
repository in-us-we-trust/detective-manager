package com.is2.detective_manager.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;
import com.is2.detective_manager.model.dto.UnknownPersonDetectedEventDto;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Registra las notificaciones del experimento en JSONL, sin publicar en RabbitMQ. */
@Slf4j
public class LocalValidationNotificationPublisher implements NotificationPublisher {

  private final ObjectMapper objectMapper;
  private final Path file;

  public LocalValidationNotificationPublisher(ObjectMapper objectMapper, Path file) {
    this.objectMapper = objectMapper;
    this.file = file.toAbsolutePath().normalize();
  }

  @Override
  public void publishIntruderDetected(IntruderNotificationMessageDto message) {
    append("INTRUDER_DETECTED", message);
  }

  @Override
  public void publishUnknownPersonDetected(UnknownPersonDetectedEventDto event) {
    append("UNKNOWN_PERSON_DETECTED", event);
  }

  private synchronized void append(String type, Object payload) {
    try {
      String entry =
          objectMapper.writeValueAsString(
              Map.of("type", type, "timestamp", Instant.now().toString(), "payload", payload));
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          entry + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "No se pudo registrar la notificación de validación", exception);
    }
    log.info("[VALIDACION] Notificación {} registrada localmente en {}", type, file);
  }
}
