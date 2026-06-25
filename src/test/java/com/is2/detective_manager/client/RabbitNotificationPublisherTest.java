package com.is2.detective_manager.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitNotificationPublisherTest {

  private static final String EXCHANGE = "notifications.exchange";
  private static final String ROUTING_KEY = "notifications.intruder";

  private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
  private final IntruderNotificationMessageDto message =
      IntruderNotificationMessageDto.builder()
          .severity("high")
          .deviceId(java.util.UUID.randomUUID())
          .title("Intruder detected")
          .message("An unknown person was detected.")
          .build();

  @Test
  void publishIntruderDetected_retriesUntilSuccessful() {
    doThrow(new AmqpException("temporary failure"))
        .doNothing()
        .when(rabbitTemplate)
        .convertAndSend(EXCHANGE, ROUTING_KEY, message);

    var publisher = new RabbitNotificationPublisher(rabbitTemplate, EXCHANGE, ROUTING_KEY, 3, 0);

    publisher.publishIntruderDetected(message);

    verify(rabbitTemplate, times(2)).convertAndSend(EXCHANGE, ROUTING_KEY, message);
  }

  @Test
  void publishIntruderDetected_throwsAfterRetriesAreExhausted() {
    doThrow(new AmqpException("permanent failure"))
        .when(rabbitTemplate)
        .convertAndSend(EXCHANGE, ROUTING_KEY, message);

    var publisher = new RabbitNotificationPublisher(rabbitTemplate, EXCHANGE, ROUTING_KEY, 3, 0);

    assertThrows(DetectiveException.class, () -> publisher.publishIntruderDetected(message));
    verify(rabbitTemplate, times(3)).convertAndSend(EXCHANGE, ROUTING_KEY, message);
  }
}
