package com.is2.detective_manager.validation.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.client.RestTemplate;

/** Profile wiring is checked without HTTP exchanges or ML replacement clients. */
class AuditProfileIsolationTest {

  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(strings = {"local", "prod", "test"})
  void otherProfilesDoNotRegisterAuditComponentsOrChangeRestTemplate(String profile) {
    Path auditDirectory = temporaryDirectory.resolve(profile);
    try (AnnotationConfigApplicationContext context = context(profile, auditDirectory)) {
      assertThat(context.getBeansOfType(ValidationAuditRecorder.class)).isEmpty();
      assertThat(context.getBeansOfType(ValidationAuditFilter.class)).isEmpty();
      assertThat(context.getBeansOfType(ValidationMlAuditConfiguration.class)).isEmpty();
      assertThat(context.getBean(RestTemplate.class).getInterceptors()).isEmpty();
      assertThat(Files.exists(auditDirectory)).isFalse();
    }
  }

  @Test
  void validationProfileRegistersAuditWithoutCallingMlOrCreatingRecordsAtStartup() {
    Path auditDirectory = temporaryDirectory.resolve("validation");
    try (AnnotationConfigApplicationContext context = context("validacion", auditDirectory)) {
      assertThat(context.getBeansOfType(ValidationAuditRecorder.class)).hasSize(1);
      assertThat(context.getBeansOfType(ValidationAuditFilter.class)).hasSize(1);
      assertThat(context.getBeansOfType(ValidationMlAuditConfiguration.class)).hasSize(1);
      assertThat(context.getBean(RestTemplate.class).getInterceptors())
          .hasSize(1)
          .first()
          .isInstanceOf(ValidationMlAuditInterceptor.class);
      assertThat(context.getBean(ValidationAuditRecorder.class).currentRequestId()).isNull();
      assertThat(Files.exists(auditDirectory)).isFalse();
    }
  }

  private AnnotationConfigApplicationContext context(String profile, Path auditDirectory) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles(profile);
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
        context,
        "ml.service.base-url=http://unused-ml.invalid:8000",
        "validation.audit.directory=" + auditDirectory);
    context.register(
        Infrastructure.class,
        ValidationAuditRecorder.class,
        ValidationAuditFilter.class,
        ValidationMlAuditConfiguration.class);
    context.refresh();
    return context;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Infrastructure {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    RestTemplate restTemplate() {
      return new RestTemplate();
    }
  }
}
