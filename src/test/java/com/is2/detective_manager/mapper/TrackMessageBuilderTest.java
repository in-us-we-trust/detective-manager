package com.is2.detective_manager.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.KnownPersonAttention;
import com.is2.detective_manager.orchestrator.evaluator.MixedRecognitionEvidence;
import com.is2.detective_manager.orchestrator.evaluator.RuleBasedDangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TrackMessageBuilderTest {

  private final TrackMessageBuilder builder =
      new TrackMessageBuilder(new RuleBasedDangerEvaluator());

  @ParameterizedTest
  @MethodSource("recognizedVisitorRequests")
  void recognizedVisitorAttention_keepsKnownPersonCodeAndExplainsTheRequest(
      String signals, String expectedTitle) {
    TrackDisplay display = builder.build(signals);

    assertEquals(TrackAppCode.KNOWN_PERSON, display.code());
    assertEquals(expectedTitle, display.title());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "COVERED_FACE",
        "MULTIPLE_PEOPLE",
        "CODE_REQUEST",
        "VERIFICATION_AVOIDANCE",
        "MONEY_REQUEST",
        "AUTHORITY",
        "LOITERING,PROLONGED_PRESENCE,REPEATED_DIRECTION_CHANGES"
      })
  void otherSuspiciousFindings_areVisibleInsteadOfTheHelpSuggestion(String finding) {
    TrackDisplay display =
        builder.build("KNOWN_PERSON,NO_THREAT_OBJECT,URGENCY,PRESSURE," + finding);

    assertEquals(TrackAppCode.KNOWN_PERSON, display.code());
    assertFalse(display.title().startsWith("Persona reconocida"));
    assertFalse(display.title().contains("podría necesitar ayuda"));
    assertFalse(display.title().contains("conviene revisar la situación"));
  }

  @Test
  void prolongedWaitPreservesTheRecognizedVisitorAttentionMessage() {
    TrackDisplay display =
        builder.build(
            "KNOWN_PERSON,NO_THREAT_OBJECT,URGENCY,PRESSURE,PROLONGED_PRESENCE,LONG_STOP");

    assertEquals(TrackAppCode.KNOWN_PERSON, display.code());
    assertTrue(display.title().startsWith(KnownPersonAttention.URGENCY_AND_PRESSURE_MESSAGE));
    assertTrue(display.title().contains("Persona presente durante un período prolongado"));
    assertTrue(display.title().contains("Persona detenida frente a la puerta"));
  }

  @Test
  void weaponKeepsItsCodeAndTitle() {
    TrackDisplay display =
        builder.build("KNOWN_PERSON,NO_THREAT_OBJECT,URGENCY,PRESSURE,WEAPON_DETECTED,THREAT");

    assertEquals(TrackAppCode.WEAPON, display.code());
    assertEquals("Persona armada en la puerta", display.title());
  }

  @Test
  void threatKeepsItsCodeAndTitle() {
    TrackDisplay display = builder.build("KNOWN_PERSON,NO_THREAT_OBJECT,URGENCY,PRESSURE,THREAT");

    assertEquals(TrackAppCode.THREAT, display.code());
    assertEquals("Amenaza detectada en la puerta", display.title());
  }

  @Test
  void mixedRecognitionShowsTheHigherAlertWithoutTheHelpSuggestion() {
    TrackDisplay display =
        builder.build("KNOWN_PERSON,UNKNOWN_PERSON,NO_THREAT_OBJECT,URGENCY,PRESSURE");

    assertEquals(TrackAppCode.KNOWN_PERSON, display.code());
    assertTrue(display.title().startsWith("Presiona para que se actúe con urgencia"));
    assertTrue(display.title().contains(MixedRecognitionEvidence.MESSAGE));
  }

  @Test
  void recognizedVisitorWithoutRequestSignals_keepsExistingTitle() {
    TrackDisplay display = builder.build("KNOWN_PERSON,NO_THREAT_OBJECT");

    assertEquals(TrackAppCode.KNOWN_PERSON, display.code());
    assertEquals("Persona reconocida en la puerta", display.title());
  }

  @Test
  void unknownVisitor_doesNotReceiveTheRecognizedVisitorTitle() {
    TrackDisplay display = builder.build("UNKNOWN_PERSON,NO_THREAT_OBJECT,URGENCY");

    assertEquals(TrackAppCode.UNKNOWN_PERSON, display.code());
    assertEquals("Persona no reconocida en la puerta", display.title());
  }

  @ParameterizedTest
  @MethodSource("ordinaryArrivals")
  void historyExplainsOrdinaryArrivalsUsingTheExistingPresentationCodes(
      String signals, TrackAppCode expectedCode, String expectedTitle, String expectedContext) {
    TrackDisplay display = builder.build(signals);

    assertEquals(expectedCode, display.code());
    assertTrue(display.title().startsWith(expectedTitle));
    assertTrue(display.title().contains(expectedContext));
  }

  @Test
  void historyJson_preservesExistingFieldNamesAndEnumValues() {
    Track track = new Track();
    track.setId(UUID.randomUUID());
    track.setDeviceId(UUID.randomUUID());
    track.setStatus(TrackStatus.CREATED);
    track.setAccumulatedSignals("KNOWN_PERSON,NO_THREAT_OBJECT,URGENCY");
    track.setFinalVerdict(Verdict.CONTINUE);
    track.setFinalSeverity(Severity.LLAMATIVO);
    TrackMapper mapper = new TrackMapper(builder);
    ObjectMapper objectMapper = new ObjectMapper();

    JsonNode listItem = objectMapper.valueToTree(mapper.toDto(track));
    JsonNode detail = objectMapper.valueToTree(mapper.toDetailDto(track, null));

    assertEquals("KNOWN_PERSON", listItem.path("code").asText());
    assertEquals("CONTINUE", listItem.path("finalVerdict").asText());
    assertEquals("LLAMATIVO", listItem.path("finalSeverity").asText());
    assertEquals("CREATED", listItem.path("status").asText());
    assertEquals(
        "Alguien conocido podría necesitar ayuda. Revisá la situación.",
        listItem.path("title").asText());
    assertEquals(listItem.path("title"), detail.path("title"));
    assertEquals(listItem.path("code"), detail.path("code"));
    assertEquals(listItem.path("finalVerdict"), detail.path("finalVerdict"));
    assertEquals(listItem.path("finalSeverity"), detail.path("finalSeverity"));
    assertFalse(listItem.has("action"));
    assertFalse(listItem.has("severity"));
  }

  private static Stream<Arguments> recognizedVisitorRequests() {
    return Stream.of(
        Arguments.of(
            "KNOWN_PERSON,NO_THREAT_OBJECT,URGENCY",
            "Alguien conocido podría necesitar ayuda. Revisá la situación."),
        Arguments.of(
            "KNOWN_PERSON,NO_THREAT_OBJECT,PRESSURE",
            "Persona reconocida que ejerce presión para que se actúe; conviene revisar la situación"),
        Arguments.of(
            "KNOWN_PERSON,NO_THREAT_OBJECT,URGENCY,PRESSURE",
            "Persona reconocida con señales de urgencia que ejerce presión; podría necesitar ayuda"));
  }

  private static Stream<Arguments> ordinaryArrivals() {
    return Stream.of(
        Arguments.of(
            "UNKNOWN_PERSON,FAST_APPROACH,PACKAGE_DETECTED,NO_SUSPICIOUS_CONVERSATION",
            TrackAppCode.UNKNOWN_PERSON,
            "No se reconoce a la persona",
            "Se detectó un paquete durante la visita"),
        Arguments.of(
            "UNKNOWN_PERSON,MULTIPLE_PEOPLE,TOOL_DETECTED,NO_SUSPICIOUS_CONVERSATION",
            TrackAppCode.UNKNOWN_PERSON,
            "Varias personas en la entrada.",
            "Se detectó una herramienta durante la visita"),
        Arguments.of(
            "KNOWN_PERSON,UNKNOWN_PERSON,NO_THREAT_OBJECT,FAST_APPROACH",
            TrackAppCode.KNOWN_PERSON,
            MixedRecognitionEvidence.MESSAGE,
            MixedRecognitionEvidence.MESSAGE),
        Arguments.of(
            "KNOWN_PERSON,UNKNOWN_PERSON,NO_THREAT_OBJECT,MULTIPLE_PEOPLE",
            TrackAppCode.KNOWN_PERSON,
            "Varias personas en la entrada.",
            MixedRecognitionEvidence.MESSAGE));
  }
}
