package com.is2.detective_manager.validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;
import com.is2.detective_manager.model.dto.UnknownPersonDetectedEventDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalValidationAdaptersTest {

  @TempDir Path directory;

  @Test
  void uploadsPreserveOriginalBytesAndReturnDistinctLocalUrls() throws Exception {
    UUID deviceId = UUID.randomUUID();
    byte[] image = {0, 1, -1, 42};
    Path images = directory.resolve("images");
    LocalValidationStorageClient storage = new LocalValidationStorageClient(images);

    String first = storage.uploadImageBytes(image, deviceId);
    String second = storage.uploadEventImage(Base64.getEncoder().encodeToString(image), deviceId);

    assertTrue(first.startsWith("/validation/media/" + deviceId + "/"));
    assertNotEquals(first, second);
    for (String url : List.of(first, second)) {
      Path imagePath =
          images.resolve(url.substring(LocalValidationStorageClient.MEDIA_URL_PREFIX.length()));
      assertArrayEquals(image, Files.readAllBytes(imagePath));
    }
  }

  @Test
  void notificationsAppendBothTypesWithTimestampAndOriginalPayload() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    Path notifications = directory.resolve("evidence/notifications.jsonl");
    LocalValidationNotificationPublisher publisher =
        new LocalValidationNotificationPublisher(objectMapper, notifications);
    UUID deviceId = UUID.randomUUID();
    IntruderNotificationMessageDto intruder =
        IntruderNotificationMessageDto.builder()
            .deviceId(deviceId)
            .severity("high")
            .message("Peligro detectado\nRevisá la entrada")
            .imageUrl("/validation/media/image.bin")
            .build();
    UnknownPersonDetectedEventDto unknownPerson =
        UnknownPersonDetectedEventDto.builder()
            .deviceId(deviceId)
            .personId(UUID.randomUUID())
            .distinctDays(3)
            .imageUrls(List.of("/validation/media/image.bin"))
            .build();

    publisher.publishIntruderDetected(intruder);
    publisher.publishUnknownPersonDetected(unknownPerson);

    List<String> entries = Files.readAllLines(notifications);
    assertEquals(2, entries.size());
    JsonNode first = objectMapper.readTree(entries.get(0));
    JsonNode second = objectMapper.readTree(entries.get(1));
    assertEquals("INTRUDER_DETECTED", first.get("type").asText());
    assertEquals("UNKNOWN_PERSON_DETECTED", second.get("type").asText());
    assertEquals(objectMapper.valueToTree(intruder), first.get("payload"));
    assertEquals(objectMapper.valueToTree(unknownPerson), second.get("payload"));
    assertTrue(Instant.parse(first.get("timestamp").asText()).isBefore(Instant.now()));
    assertTrue(Instant.parse(second.get("timestamp").asText()).isBefore(Instant.now()));
  }
}
