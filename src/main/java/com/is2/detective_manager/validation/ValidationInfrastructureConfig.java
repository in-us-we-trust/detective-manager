package com.is2.detective_manager.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.NotificationPublisher;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile("validacion")
@Slf4j
public class ValidationInfrastructureConfig implements WebMvcConfigurer {

  private final Path mediaDirectory;

  public ValidationInfrastructureConfig(
      @Value("${validation.media.directory:./validation-data/images}") String mediaDirectory) {
    this.mediaDirectory = Path.of(mediaDirectory).toAbsolutePath().normalize();
    log.warn(
        "[VALIDACION] Infraestructura local activa: imágenes en disco y notificaciones en JSONL");
  }

  @Bean
  public CloudinaryStorageClient validationStorageClient() {
    return new LocalValidationStorageClient(mediaDirectory);
  }

  @Bean
  public NotificationPublisher validationNotificationPublisher(
      ObjectMapper objectMapper,
      @Value("${validation.notifications.file:./validation-data/notifications.jsonl}")
          String notificationsFile) {
    return new LocalValidationNotificationPublisher(objectMapper, Path.of(notificationsFile));
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String location = mediaDirectory.toUri().toString();
    if (!location.endsWith("/")) {
      location += "/";
    }
    registry
        .addResourceHandler(LocalValidationStorageClient.MEDIA_URL_PREFIX + "**")
        .addResourceLocations(location);
  }
}
