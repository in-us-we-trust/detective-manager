package com.is2.detective_manager.configuration;

import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.client.RabbitNotificationPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!validacion")
public class RabbitMqConfig {

  @Bean
  public MessageConverter rabbitMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  @Bean
  public NotificationPublisher notificationPublisher(
      RabbitTemplate rabbitTemplate,
      @Value("${rabbitmq.notifications.exchange}") String exchange,
      @Value("${rabbitmq.notifications.intruder-routing-key}") String intruderRoutingKey,
      @Value("${rabbitmq.notifications.unknown-person-routing-key}") String unknownPersonRoutingKey,
      @Value("${rabbitmq.notifications.publish.max-attempts}") int maxAttempts,
      @Value("${rabbitmq.notifications.publish.retry-delay-ms}") long retryDelayMs) {
    return new RabbitNotificationPublisher(
        rabbitTemplate,
        exchange,
        intruderRoutingKey,
        unknownPersonRoutingKey,
        maxAttempts,
        retryDelayMs);
  }
}
