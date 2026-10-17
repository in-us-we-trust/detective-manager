package com.is2.detective_manager.orchestrator.step.conversationanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.model.dto.ConversationAnalysisResponseDto;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class RealConversationAnalyzerTest {

  private static final String URL = "http://ml-service/api/ml-service/v1/analysis/conversation";

  @ParameterizedTest
  @ValueSource(strings = {"null", "{}"})
  void analyze_missingResponseFieldsFailInsteadOfReturningEmptyEvidence(String json)
      throws Exception {
    RealConversationAnalyzer analyzer = analyzer(json);

    assertThrows(DetectiveException.class, () -> analyzer.analyze(new byte[] {1}));
  }

  @Test
  void analyze_preservesUsableTranscriptAndValidEmptyEvidence() throws Exception {
    ConversationAnalysisResult result =
        analyzer("{\"transcript\":\"Buenas tardes\",\"evidences\":[]}").analyze(new byte[] {1});

    assertEquals("Buenas tardes", result.transcript());
    assertEquals(AnalysisQuality.VALID, result.quality());
    assertEquals(List.of(), result.evidences());
  }

  @Test
  void analyze_missingTranscriptPreservesValidThreatAsIncomplete() throws Exception {
    RealConversationAnalyzer analyzer =
        analyzer("{\"evidences\":[{\"type\":\"THREAT\",\"score\":0.9}]}");

    StepResult result =
        new ConversationAnalysisStep(analyzer)
            .execute(StepContext.builder().payload(new byte[] {1}).build());

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertEquals(List.of(Signal.THREAT), result.getSignals());
  }

  @Test
  void analyze_missingEvidenceDoesNotTreatUsableTranscriptAsSafe() throws Exception {
    RealConversationAnalyzer analyzer = analyzer("{\"transcript\":\"Buenas tardes\"}");

    StepResult result =
        new ConversationAnalysisStep(analyzer)
            .execute(StepContext.builder().payload(new byte[] {1}).build());

    assertEquals(AnalysisQuality.INSUFFICIENT, result.getQuality());
    assertTrue(result.getSignals().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{\"type\":\"URGENCY\"}",
        "{\"score\":0.9}",
        "{\"type\":\"\",\"score\":0.9}",
        "{\"type\":\"URGENCY\",\"score\":-0.1}",
        "{\"type\":\"URGENCY\",\"score\":1.1}"
      })
  void analyze_partialEvidenceRetainsValidThreatAndMarksIncomplete(String invalidEvidence)
      throws Exception {
    String json =
        "{\"transcript\":\"Amenaza\",\"evidences\":["
            + invalidEvidence
            + ",{\"type\":\"THREAT\",\"score\":0.9}]}";

    ConversationAnalysisResult result = analyzer(json).analyze(new byte[] {1});

    assertEquals(AnalysisQuality.INSUFFICIENT, result.quality());
    assertEquals(List.of(new Evidence("THREAT", 0.9)), result.evidences());
  }

  @Test
  void analyze_nonFiniteScoreIsInsufficient() {
    ConversationAnalysisResponseDto.EvidenceDto evidence =
        new ConversationAnalysisResponseDto.EvidenceDto();
    evidence.setType("THREAT");
    evidence.setScore(Double.NaN);
    ConversationAnalysisResponseDto response = new ConversationAnalysisResponseDto();
    response.setTranscript("Texto");
    response.setEvidences(List.of(evidence));

    ConversationAnalysisResult result = analyzer(response).analyze(new byte[] {1});

    assertEquals(AnalysisQuality.INSUFFICIENT, result.quality());
    assertTrue(result.evidences().isEmpty());
  }

  @Test
  void analyze_mlRequestFailureIsNotAValidEmptyAnalysis() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForObject(
            eq(URL), any(HttpEntity.class), eq(ConversationAnalysisResponseDto.class)))
        .thenThrow(new RestClientException("unavailable"));
    RealConversationAnalyzer analyzer =
        new RealConversationAnalyzer(restTemplate, "http://ml-service");

    assertThrows(DetectiveException.class, () -> analyzer.analyze(new byte[] {1}));
  }

  private RealConversationAnalyzer analyzer(String json) throws Exception {
    return analyzer(new ObjectMapper().readValue(json, ConversationAnalysisResponseDto.class));
  }

  private RealConversationAnalyzer analyzer(ConversationAnalysisResponseDto response) {
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForObject(
            eq(URL), any(HttpEntity.class), eq(ConversationAnalysisResponseDto.class)))
        .thenReturn(response);
    return new RealConversationAnalyzer(restTemplate, "http://ml-service");
  }
}
