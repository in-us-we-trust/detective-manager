package com.is2.detective_manager.client;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.UnknownPersonDetectedEventDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Slf4j
public class RabbitUnknownPersonEventPublisher implements UnknownPersonEventPublisher {

  private final RabbitTemplate rabbitTemplate;
  private final String exchange;
  private final String routingKey;
  private final int maxAttempts;
  private final long retryDelayMs;

  public RabbitUnknownPersonEventPublisher(
      RabbitTemplate rabbitTemplate,
      String exchange,
      String routingKey,
      int maxAttempts,
      long retryDelayMs) {
    this.rabbitTemplate = rabbitTemplate;
    this.exchange = exchange;
    this.routingKey = routingKey;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.retryDelayMs = retryDelayMs;
  }

  @Override
  public void publishUnknownPersonDetected(UnknownPersonDetectedEventDto event) {
    log.info(
        "Publishing unknown person notification for device {}, person {}",
        event.getDeviceId(),
        event.getPersonId());

    AmqpException lastException = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        rabbitTemplate.convertAndSend(exchange, routingKey, event);
        return;
      } catch (AmqpException exception) {
        lastException = exception;
        log.warn(
            "Failed to publish unknown person event to RabbitMQ. Attempt {}/{}",
            attempt,
            maxAttempts,
            exception);
        waitBeforeRetry(attempt);
      }
    }

    log.error("Failed to publish unknown person event to RabbitMQ after retries", lastException);
    throw new DetectiveException(ExceptionEnum.NOTIFICATION_ERROR);
  }

  private void waitBeforeRetry(int attempt) {
    if (attempt >= maxAttempts || retryDelayMs <= 0) {
      return;
    }

    try {
      Thread.sleep(retryDelayMs);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new DetectiveException(ExceptionEnum.NOTIFICATION_ERROR);
    }
  }
}
