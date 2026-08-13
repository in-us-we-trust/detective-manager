package com.is2.detective_manager.orchestrator.step.conversationanalysis;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.ConversationAnalysisResponseDto;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Real: llama al ML /api/ml-service/v1/analysis/conversation con el audio como
 * multipart (campo visitor_audio) y devuelve las evidencias del dominio del ML.
 */
@Slf4j
@Component
@Profile("!mock-conversation")
public class RealConversationAnalyzer implements ConversationAnalyzer {

  private final RestTemplate restTemplate;
  private final String baseUrl;

  public RealConversationAnalyzer(
      RestTemplate restTemplate, @Value("${ml.service.base-url}") String baseUrl) {
    this.restTemplate = restTemplate;
    this.baseUrl = baseUrl;
  }

  @Override
  public List<Evidence> analyze(byte[] audio) {
    String url = baseUrl + "/api/ml-service/v1/analysis/conversation";
    log.info("Llamando al ML de conversación en: {}", url);

    // Cuerpo multipart: visitor_audio = el archivo de audio.
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);

    // ByteArrayResource con un nombre de archivo (el ML espera un archivo).
    ByteArrayResource audioResource =
        new ByteArrayResource(audio) {
          @Override
          public String getFilename() {
            return "visitor_audio";
          }
        };

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("visitor_audio", audioResource);

    HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

    ConversationAnalysisResponseDto response;
    try {
      response = restTemplate.postForObject(url, entity, ConversationAnalysisResponseDto.class);
    } catch (RestClientException exception) {
      log.error("Falló la llamada al ML de conversación", exception);
      throw new DetectiveException(ExceptionEnum.ML_SERVICE_ERROR);
    }

    if (response == null || response.getEvidences() == null) {
      log.warn("El ML de conversación devolvió una respuesta vacía");
      return List.of();
    }

    // Traducimos el DTO del ML a nuestro modelo Evidence.
    return response.getEvidences().stream()
        .map(e -> new Evidence(e.getType(), e.getScore()))
        .toList();
  }
}
