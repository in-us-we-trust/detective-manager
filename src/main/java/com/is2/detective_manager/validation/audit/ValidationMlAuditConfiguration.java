package com.is2.detective_manager.validation.audit;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

/** Adds the observer only to RestTemplate instances built under the validation profile. */
@Configuration
@Profile("validacion")
public class ValidationMlAuditConfiguration {

  @Bean
  public static BeanPostProcessor validationMlAuditPostProcessor(
      ObjectProvider<ValidationAuditRecorder> recorder, Environment environment) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RestTemplate restTemplate
            && restTemplate.getInterceptors().stream()
                .noneMatch(ValidationMlAuditInterceptor.class::isInstance)) {
          List<ClientHttpRequestInterceptor> interceptors =
              new ArrayList<>(restTemplate.getInterceptors());
          // The existing logging observer wraps this one; the response replays for both readers.
          interceptors.add(
              new ValidationMlAuditInterceptor(
                  recorder.getObject(), environment.getRequiredProperty("ml.service.base-url")));
          restTemplate.setInterceptors(interceptors);
        }
        return bean;
      }
    };
  }
}
