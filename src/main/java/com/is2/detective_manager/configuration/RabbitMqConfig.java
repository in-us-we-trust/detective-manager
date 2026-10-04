package com.is2.detective_manager.configuration;

import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.client.RabbitNotificationPublisher;
import com.is2.detective_manager.client.RabbitUnknownPersonEventPublisher;
import com.is2.detective_manager.client.UnknownPersonEventPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

  @Bean
  public MessageConverter rabbitMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  @Bean
  public NotificationPublisher notificationPublisher(
      RabbitTemplate rabbitTemplate,
      @Value("${rabbitmq.notifications.exchange}") String exchange,
      @Value("${rabbitmq.notifications.routing-key}") String routingKey,
      @Value("${rabbitmq.notifications.publish.max-attempts}") int maxAttempts,
      @Value("${rabbitmq.notifications.publish.retry-delay-ms}") long retryDelayMs) {
    return new RabbitNotificationPublisher(
        rabbitTemplate, exchange, routingKey, maxAttempts, retryDelayMs);
  }

  @Bean
  public UnknownPersonEventPublisher unknownPersonEventPublisher(
      RabbitTemplate rabbitTemplate,
      @Value("${rabbitmq.unknown-person.exchange}") String exchange,
      @Value("${rabbitmq.unknown-person.routing-key}") String routingKey,
      @Value("${rabbitmq.notifications.publish.max-attempts}") int maxAttempts,
      @Value("${rabbitmq.notifications.publish.retry-delay-ms}") long retryDelayMs) {
    return new RabbitUnknownPersonEventPublisher(
        rabbitTemplate, exchange, routingKey, maxAttempts, retryDelayMs);
  }
}
