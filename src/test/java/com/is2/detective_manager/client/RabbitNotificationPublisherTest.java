package com.is2.detective_manager.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;
import com.is2.detective_manager.model.dto.UnknownPersonDetectedEventDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitNotificationPublisherTest {

  private static final String EXCHANGE = "notifications";
  private static final String INTRUDER_ROUTING_KEY = "notification.intruder";
  private static final String UNKNOWN_PERSON_ROUTING_KEY = "notification.unknown-person";
  private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
  private final IntruderNotificationMessageDto intruderMessage =
      IntruderNotificationMessageDto.builder()
          .severity("high")
          .deviceId(UUID.randomUUID())
          .title("Intruder detected")
          .message("An unknown person was detected.")
          .build();
  private final UnknownPersonDetectedEventDto suggestion =
      UnknownPersonDetectedEventDto.builder()
          .deviceId(UUID.randomUUID())
          .personId(UUID.randomUUID())
          .distinctDays(4)
          .imageUrls(List.of("https://cdn.example/a.jpg"))
          .build();

  @Test
  void publishIntruderDetected_retriesUntilSuccessful() {
    doThrow(new AmqpException("temporary failure"))
        .doNothing()
        .when(rabbitTemplate)
        .convertAndSend(EXCHANGE, INTRUDER_ROUTING_KEY, intruderMessage);

    publisher().publishIntruderDetected(intruderMessage);

    verify(rabbitTemplate, times(2))
        .convertAndSend(EXCHANGE, INTRUDER_ROUTING_KEY, intruderMessage);
  }

  @Test
  void publishIntruderDetected_throwsAfterRetriesAreExhausted() {
    doThrow(new AmqpException("permanent failure"))
        .when(rabbitTemplate)
        .convertAndSend(EXCHANGE, INTRUDER_ROUTING_KEY, intruderMessage);

    assertThrows(
        DetectiveException.class, () -> publisher().publishIntruderDetected(intruderMessage));
    verify(rabbitTemplate, times(3))
        .convertAndSend(EXCHANGE, INTRUDER_ROUTING_KEY, intruderMessage);
  }

  @Test
  void publishUnknownPersonDetected_usesUnknownPersonRoutingKey() {
    publisher().publishUnknownPersonDetected(suggestion);

    verify(rabbitTemplate).convertAndSend(EXCHANGE, UNKNOWN_PERSON_ROUTING_KEY, suggestion);
  }

  private RabbitNotificationPublisher publisher() {
    return new RabbitNotificationPublisher(
        rabbitTemplate, EXCHANGE, INTRUDER_ROUTING_KEY, UNKNOWN_PERSON_ROUTING_KEY, 3, 0);
  }
}
