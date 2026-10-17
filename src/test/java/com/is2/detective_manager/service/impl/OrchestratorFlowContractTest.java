package com.is2.detective_manager.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.mapper.TrackAppCode;
import com.is2.detective_manager.mapper.TrackMapper;
import com.is2.detective_manager.mapper.TrackMessageBuilder;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.entity.AssessmentRecord;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.model.entity.TrackStatus;
import com.is2.detective_manager.model.generic.StandardResponse;
import com.is2.detective_manager.orchestrator.AnalysisPipeline;
import com.is2.detective_manager.orchestrator.AnalysisQuality;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.KnownPersonAttention;
import com.is2.detective_manager.orchestrator.evaluator.MixedRecognitionEvidence;
import com.is2.detective_manager.orchestrator.evaluator.RuleBasedDangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalysisResult;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalysisStep;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalyzer;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.Evidence;
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionOutcome;
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionStep;
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognizer;
import com.is2.detective_manager.orchestrator.step.objectrecognition.DetectedObject;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectDetectionResult;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectRecognitionStep;
import com.is2.detective_manager.orchestrator.step.objectrecognition.ObjectRecognizer;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoMetadata;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoObservation;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisStep;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalyzer;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoFeatureCalculator;
import com.is2.detective_manager.repository.AssessmentRecordRepository;
import com.is2.detective_manager.repository.TrackRepository;
import com.is2.detective_manager.service.PersonService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Exercises the real pipeline, persistence consolidation, evaluator and public serialization. */
@ExtendWith(MockitoExtension.class)
class OrchestratorFlowContractTest {

  private static final UUID TRACK_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
  private static final UUID DEVICE_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
  private static final byte[] PAYLOAD = {1, 2, 3};
  private static final Set<String> RESPONSE_FIELDS =
      Set.of(
          "trackId",
          "chunkType",
          "finalVerdict",
          "finalSeverity",
          "message",
          "additionalInformation");

  @Mock private TrackRepository trackRepository;
  @Mock private AssessmentRecordRepository assessmentRepository;
  @Mock private FaceRecognizer faceRecognizer;
  @Mock private ObjectRecognizer objectRecognizer;
  @Mock private ConversationAnalyzer conversationAnalyzer;
  @Mock private VideoAnalyzer videoAnalyzer;
  @Mock private NotificationPublisher notificationPublisher;
  @Mock private CloudinaryStorageClient cloudinaryStorageClient;
  @Mock private PersonService personService;

  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final RuleBasedDangerEvaluator evaluator = new RuleBasedDangerEvaluator();
  private final List<AssessmentRecord> records = new ArrayList<>();
  private Track storedTrack;
  private OrchestratorServiceImpl service;

  @BeforeEach
  void setUp() {
    when(trackRepository.findById(TRACK_ID))
        .thenAnswer(ignored -> Optional.ofNullable(storedTrack));
    when(trackRepository.save(any()))
        .thenAnswer(invocation -> storedTrack = invocation.getArgument(0));
    when(assessmentRepository.save(any()))
        .thenAnswer(
            invocation -> {
              AssessmentRecord record = invocation.getArgument(0);
              records.add(record);
              return record;
            });
    when(assessmentRepository.findAllByTrackIdOrderByCreatedAtAsc(TRACK_ID))
        .thenAnswer(ignored -> List.copyOf(records));

    VideoAnalysisProperties properties = new VideoAnalysisProperties();
    AnalysisPipeline pipeline =
        new AnalysisPipeline(
            new FaceRecognitionStep(faceRecognizer),
            new ObjectRecognitionStep(objectRecognizer),
            new ConversationAnalysisStep(conversationAnalyzer),
            new VideoAnalysisStep(
                videoAnalyzer, new VideoFeatureCalculator(properties), properties));
    service =
        new OrchestratorServiceImpl(
            pipeline,
            new TrackPersistenceServiceImpl(
                trackRepository, assessmentRepository, evaluator, properties),
            notificationPublisher,
            cloudinaryStorageClient,
            personService,
            executor);
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n", "\u2003"})
  void recognizedVisitorWithoutAUsableName_keepsGenericMessageAndPublicContract(String name)
      throws Exception {
    recognizedImageWithoutThreats();
    when(faceRecognizer.recognize(PAYLOAD, DEVICE_ID))
        .thenReturn(FaceRecognitionOutcome.known(name));

    EventChunkResponseDto response = send(ChunkType.IMAGE);

    assertEquals("Persona reconocida", response.getMessage());
    assertEquals("SAFE", response.getFinalVerdict());
    assertEquals("NORMAL", response.getFinalSeverity());
    assertNull(response.getAdditionalInformation());
    assertResponseContract(response);
  }

  @Test
  void recognizedVisitorWithUrgency_reopensTrackAndKeepsResponseAndDisplayContracts()
      throws Exception {
    recognizedImageWithoutThreats();
    EventChunkResponseDto image = send(ChunkType.IMAGE);
    assertEquals("SAFE", image.getFinalVerdict());
    assertEquals("NORMAL", image.getFinalSeverity());
    assertEquals(TrackStatus.CLOSED, storedTrack.getStatus());
    assertEquals("Juan Pérez", image.getAdditionalInformation().get("personName"));
    assertResponseContract(image);

    when(conversationAnalyzer.analyze(PAYLOAD))
        .thenReturn(
            new ConversationAnalysisResult(
                "Necesito ayuda ahora", List.of(new Evidence("URGENCY", 0.95))));
    EventChunkResponseDto audio = send(ChunkType.AUDIO);

    assertEquals("CONTINUE", audio.getFinalVerdict());
    assertEquals("LLAMATIVO", audio.getFinalSeverity());
    assertEquals(KnownPersonAttention.URGENCY_MESSAGE, audio.getMessage());
    assertEquals(Verdict.CONTINUE, storedTrack.getFinalVerdict());
    assertEquals(Severity.LLAMATIVO, storedTrack.getFinalSeverity());
    assertEquals(TrackStatus.CREATED, storedTrack.getStatus());
    assertFalse(storedTrack.isNotificationSent());
    assertTrue(records.stream().anyMatch(record -> "URGENCY".equals(record.getSignals())));
    assertFalse(storedTrack.getAccumulatedSignals().contains("NO_SUSPICIOUS_CONVERSATION"));
    assertResponseContract(audio);

    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    assertEquals(TrackAppCode.KNOWN_PERSON, mapper.toDto(storedTrack).getCode());
    assertEquals(
        KnownPersonAttention.URGENCY_MESSAGE, mapper.toDetailDto(storedTrack, null).getTitle());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void recognizedVisitorWithUrgencyAndPressure_keepsAttentionWithoutDeclaringDanger() {
    recognizedImageWithoutThreats();
    send(ChunkType.IMAGE);
    when(conversationAnalyzer.analyze(PAYLOAD))
        .thenReturn(
            new ConversationAnalysisResult(
                "Abrime ahora, te estoy esperando",
                List.of(new Evidence("URGENCY", 0.95), new Evidence("PRESSURE", 0.91))));

    EventChunkResponseDto response = send(ChunkType.AUDIO);

    assertEquals("CONTINUE", response.getFinalVerdict());
    assertEquals("LLAMATIVO", response.getFinalSeverity());
    assertEquals(KnownPersonAttention.URGENCY_AND_PRESSURE_MESSAGE, response.getMessage());
    assertEquals(TrackStatus.CREATED, storedTrack.getStatus());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void audioFailureAfterValidSafeImage_preservesEvidenceAndReportsFailure() {
    recognizedImageWithoutThreats();
    send(ChunkType.IMAGE);
    when(conversationAnalyzer.analyze(PAYLOAD))
        .thenThrow(new IllegalStateException("ML unavailable"));

    EventChunkResponseDto response = send(ChunkType.AUDIO);

    assertEquals("SAFE", response.getFinalVerdict());
    assertEquals("NORMAL", response.getFinalSeverity());
    assertTrue(response.getMessage().contains("Persona reconocida"));
    assertTrue(response.getMessage().contains("No se pudo completar el análisis de audio"));
    assertFalse(storedTrack.getAccumulatedSignals().contains("NO_SUSPICIOUS_CONVERSATION"));
    assertEquals(TrackStatus.CLOSED, storedTrack.getStatus());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void conversationFindingsFromSeparateChunks_formAVisitPatternWithTheSameResponseContract()
      throws Exception {
    when(conversationAnalyzer.analyze(PAYLOAD))
        .thenReturn(
            new ConversationAnalysisResult(
                "Soy inspector", List.of(new Evidence("AUTHORITY", 0.95))),
            new ConversationAnalysisResult(
                "Necesito que pagues un arancel", List.of(new Evidence("MONEY_REQUEST", 0.95))));

    EventChunkResponseDto first = send(ChunkType.AUDIO);
    EventChunkResponseDto second = send(ChunkType.AUDIO);

    assertEquals("LLAMATIVO", first.getFinalSeverity());
    assertEquals("CONTINUE", first.getFinalVerdict());
    assertEquals("SOSPECHOSO", second.getFinalSeverity());
    assertEquals("CONTINUE", second.getFinalVerdict());
    assertEquals(
        "Verificá la identidad y el motivo del pedido antes de pagar.", second.getMessage());
    assertEquals(2, records.stream().map(AssessmentRecord::getRequestId).distinct().count());
    assertEquals(TrackStatus.CREATED, storedTrack.getStatus());
    assertResponseContract(first);
    assertResponseContract(second);

    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    assertEquals(TrackAppCode.ACTIVITY, mapper.toDto(storedTrack).getCode());
    assertEquals(second.getMessage(), mapper.toDetailDto(storedTrack, null).getTitle());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void authorityAndMoneyAfterSafeImage_requireAttentionAndKeepTheKnownPersonCode()
      throws Exception {
    recognizedImageWithoutThreats();
    assertEquals("SAFE", send(ChunkType.IMAGE).getFinalVerdict());
    when(conversationAnalyzer.analyze(PAYLOAD))
        .thenReturn(
            new ConversationAnalysisResult(
                "Soy inspector y necesito que pagues un arancel",
                List.of(new Evidence("AUTHORITY", 0.95), new Evidence("MONEY_REQUEST", 0.95))));

    EventChunkResponseDto response = send(ChunkType.AUDIO);

    assertEquals("SOSPECHOSO", response.getFinalSeverity());
    assertEquals("CONTINUE", response.getFinalVerdict());
    assertEquals(TrackStatus.CREATED, storedTrack.getStatus());
    assertTrue(
        response
            .getMessage()
            .startsWith("Verificá la identidad y el motivo del pedido antes de pagar."));
    assertResponseContract(response);
    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    assertEquals(TrackAppCode.KNOWN_PERSON, mapper.toDto(storedTrack).getCode());
    assertEquals(response.getMessage(), mapper.toDetailDto(storedTrack, null).getTitle());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void coveredFaceInImageAndFastApproachInVideo_areDescribedAsVisitEvidence() throws Exception {
    coveredFaceImage();
    send(ChunkType.IMAGE);
    fastApproachVideo();

    EventChunkResponseDto response = send(ChunkType.VIDEO);

    assertEquals("SOSPECHOSO", response.getFinalSeverity());
    assertEquals("CONTINUE", response.getFinalVerdict());
    assertTrue(
        response
            .getMessage()
            .startsWith("Rostro cubierto: no permite ver bien quién es. Revisá antes de abrir."));
    assertFalse(response.getMessage().contains("Persona reconocida acercándose"));
    assertEquals(2, records.stream().map(AssessmentRecord::getRequestId).distinct().count());
    assertResponseContract(response);
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void physicalVisitPatternTakesTheTitleRegardlessOfAudioArrivalOrder(boolean conversationFirst)
      throws Exception {
    coveredFaceImage();
    fastApproachVideo();
    when(conversationAnalyzer.analyze(PAYLOAD))
        .thenReturn(
            new ConversationAnalysisResult(
                "Soy inspector y necesito un pago",
                List.of(new Evidence("AUTHORITY", 0.95), new Evidence("MONEY_REQUEST", 0.95))));

    EventChunkResponseDto response;
    if (conversationFirst) {
      send(ChunkType.AUDIO);
      send(ChunkType.IMAGE);
      response = send(ChunkType.VIDEO);
    } else {
      send(ChunkType.IMAGE);
      send(ChunkType.VIDEO);
      response = send(ChunkType.AUDIO);
    }

    assertEquals("SOSPECHOSO", response.getFinalSeverity());
    assertEquals("CONTINUE", response.getFinalVerdict());
    assertTrue(
        response
            .getMessage()
            .startsWith("Rostro cubierto: no permite ver bien quién es. Revisá antes de abrir."));
    assertTrue(
        response
            .getMessage()
            .contains("Verificá la identidad y el motivo del pedido antes de pagar."));
    assertEquals(3, records.stream().map(AssessmentRecord::getRequestId).distinct().count());
    assertEquals(TrackStatus.CREATED, storedTrack.getStatus());
    assertResponseContract(response);
    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    assertEquals(TrackAppCode.KNOWN_PERSON, mapper.toDto(storedTrack).getCode());
    assertEquals(response.getMessage(), mapper.toDetailDto(storedTrack, null).getTitle());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void contextObjectsInImageAreStoredAndDisplayedWithTheExistingContracts(boolean known)
      throws Exception {
    when(faceRecognizer.recognize(PAYLOAD, DEVICE_ID))
        .thenReturn(
            known ? FaceRecognitionOutcome.known("Juan Pérez") : FaceRecognitionOutcome.unknown());
    contextObjectsImage();

    EventChunkResponseDto response = send(ChunkType.IMAGE);

    assertEquals(known ? "NORMAL" : "LLAMATIVO", response.getFinalSeverity());
    assertEquals(known ? "SAFE" : "CONTINUE", response.getFinalVerdict());
    assertEquals(known ? TrackStatus.CLOSED : TrackStatus.CREATED, storedTrack.getStatus());
    assertTrue(response.getMessage().contains("Se detectó una escalera durante la visita"));
    assertTrue(response.getMessage().contains("Se detectó una herramienta durante la visita"));
    assertTrue(response.getMessage().contains("Se detectó un paquete durante la visita"));
    for (String signal : List.of("LADDER_DETECTED", "TOOL_DETECTED", "PACKAGE_DETECTED")) {
      assertTrue(storedTrack.getAccumulatedSignals().contains(signal));
      assertTrue(records.stream().anyMatch(record -> record.getSignals().contains(signal)));
    }
    assertFalse(storedTrack.isNotificationSent());
    assertResponseContract(response);

    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    JsonNode listItem = objectMapper.valueToTree(mapper.toDto(storedTrack));
    JsonNode detail = objectMapper.valueToTree(mapper.toDetailDto(storedTrack, null));
    assertEquals(
        Set.of(
            "code",
            "title",
            "trackId",
            "deviceId",
            "status",
            "currentStep",
            "accumulatedSignals",
            "finalVerdict",
            "finalSeverity",
            "createdAt",
            "updatedAt"),
        fieldNames(listItem));
    assertEquals(
        Set.of(
            "code", "title", "finalVerdict", "finalSeverity", "createdAt", "updatedAt", "imageUrl"),
        fieldNames(detail));
    assertEquals(known ? "KNOWN_PERSON" : "UNKNOWN_PERSON", listItem.get("code").asText());
    assertTrue(listItem.get("accumulatedSignals").isTextual());
    assertEquals(storedTrack.getAccumulatedSignals(), listItem.get("accumulatedSignals").asText());
    assertEquals(listItem.get("code"), detail.get("code"));
    assertEquals(listItem.get("title"), detail.get("title"));
    assertTrue(response.getMessage().startsWith(detail.get("title").asText()));
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void contextObjectsDoNotChangeTheKnownVisitorAttentionOnLaterAudio() throws Exception {
    when(faceRecognizer.recognize(PAYLOAD, DEVICE_ID))
        .thenReturn(FaceRecognitionOutcome.known("Juan Pérez"));
    contextObjectsImage();
    send(ChunkType.IMAGE);
    when(conversationAnalyzer.analyze(PAYLOAD))
        .thenReturn(
            new ConversationAnalysisResult(
                "Necesito ayuda ahora", List.of(new Evidence("URGENCY", 0.95))));

    EventChunkResponseDto response = send(ChunkType.AUDIO);

    assertEquals("LLAMATIVO", response.getFinalSeverity());
    assertEquals("CONTINUE", response.getFinalVerdict());
    assertTrue(response.getMessage().startsWith(KnownPersonAttention.URGENCY_MESSAGE));
    assertTrue(response.getMessage().contains("Se detectó una escalera durante la visita"));
    assertTrue(response.getMessage().contains("Se detectó una herramienta durante la visita"));
    assertTrue(response.getMessage().contains("Se detectó un paquete durante la visita"));
    assertEquals(TrackStatus.CREATED, storedTrack.getStatus());
    assertResponseContract(response);
    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    assertEquals(TrackAppCode.KNOWN_PERSON, mapper.toDto(storedTrack).getCode());
    assertEquals(response.getMessage(), mapper.toDetailDto(storedTrack, null).getTitle());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void contextObjectsAloneRemainInformativeWhenRecognitionFails() throws Exception {
    when(faceRecognizer.recognize(PAYLOAD, DEVICE_ID))
        .thenThrow(new IllegalStateException("ML unavailable"));
    contextObjectsImage();

    EventChunkResponseDto response = send(ChunkType.IMAGE);

    assertEquals("NORMAL", response.getFinalSeverity());
    assertEquals("CONTINUE", response.getFinalVerdict());
    assertTrue(
        response
            .getMessage()
            .contains("No se pudo completar el análisis de reconocimiento facial"));
    assertTrue(response.getMessage().contains("Se detectó un paquete durante la visita"));
    assertEquals(TrackStatus.CREATED, storedTrack.getStatus());
    assertResponseContract(response);
    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    assertEquals(TrackAppCode.ACTIVITY, mapper.toDto(storedTrack).getCode());
    assertTrue(
        mapper
            .toDto(storedTrack)
            .getTitle()
            .contains("Se detectó una herramienta durante la visita"));
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void knownAndUnknownDetectionsKeepTheVisitOpenRegardlessOfArrivalOrder(boolean knownFirst)
      throws Exception {
    FaceRecognitionOutcome known = FaceRecognitionOutcome.known("Juan Pérez");
    FaceRecognitionOutcome unknown = FaceRecognitionOutcome.unknown();
    when(faceRecognizer.recognize(PAYLOAD, DEVICE_ID))
        .thenReturn(knownFirst ? known : unknown, knownFirst ? unknown : known);
    when(objectRecognizer.detectObjects(PAYLOAD))
        .thenReturn(
            new ObjectDetectionResult(
                List.of(new DetectedObject("person", 0.95)), Map.of("person", 1)));

    EventChunkResponseDto first = send(ChunkType.IMAGE);
    EventChunkResponseDto second = send(ChunkType.IMAGE);

    assertEquals(knownFirst ? "SAFE" : "CONTINUE", first.getFinalVerdict());
    assertEquals("CONTINUE", second.getFinalVerdict());
    assertEquals("LLAMATIVO", second.getFinalSeverity());
    assertTrue(second.getMessage().startsWith(MixedRecognitionEvidence.MESSAGE));
    assertEquals(TrackStatus.CREATED, storedTrack.getStatus());
    assertTrue(storedTrack.getAccumulatedSignals().contains("KNOWN_PERSON"));
    assertTrue(storedTrack.getAccumulatedSignals().contains("UNKNOWN_PERSON"));
    assertEquals(4, records.size());
    assertResponseContract(first);
    assertResponseContract(second);
    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    assertEquals(TrackAppCode.KNOWN_PERSON, mapper.toDto(storedTrack).getCode());
    assertEquals(
        MixedRecognitionEvidence.MESSAGE, mapper.toDetailDto(storedTrack, null).getTitle());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void partialWeaponDetectionAndLaterFailure_preserveDangerAndNotifyOnlyOnce() {
    when(faceRecognizer.recognize(PAYLOAD, DEVICE_ID))
        .thenReturn(FaceRecognitionOutcome.known("Juan Pérez"));
    when(objectRecognizer.detectObjects(PAYLOAD))
        .thenReturn(
            new ObjectDetectionResult(
                List.of(new DetectedObject("Firearm", 0.95)),
                Map.of(),
                AnalysisQuality.INSUFFICIENT));
    when(cloudinaryStorageClient.uploadImageBytes(PAYLOAD, DEVICE_ID))
        .thenReturn("https://cdn.example/image.jpg");
    EventChunkResponseDto image = send(ChunkType.IMAGE);
    assertEquals("DANGER", image.getFinalVerdict());
    assertEquals("PELIGROSO", image.getFinalSeverity());
    assertTrue(image.getMessage().startsWith("La persona está armada"));
    assertFalse(storedTrack.getAccumulatedSignals().contains("NO_THREAT_OBJECT"));
    assertTrue(storedTrack.isNotificationSent());

    when(conversationAnalyzer.analyze(PAYLOAD))
        .thenThrow(new IllegalStateException("ML unavailable"));
    EventChunkResponseDto audio = send(ChunkType.AUDIO);
    assertEquals("DANGER", audio.getFinalVerdict());
    assertTrue(audio.getMessage().startsWith("La persona está armada"));
    assertTrue(audio.getMessage().contains("No se pudo completar el análisis de audio"));
    verify(notificationPublisher, times(1)).publishIntruderDetected(any());
  }

  @Test
  void fourFiveSecondClipsReopenSafeVisitAndPreserveDisplayAndResponseContracts() throws Exception {
    recognizedImageWithoutThreats();
    assertEquals("SAFE", send(ChunkType.IMAGE).getFinalVerdict());
    when(videoAnalyzer.analyze(PAYLOAD)).thenReturn(stationaryClip(5, 71));
    for (int number = 1; number <= 3; number++) {
      assertEquals("SAFE", sendVideo(number).getFinalVerdict());
    }
    assertEquals("SAFE", sendVideo(2).getFinalVerdict()); // Reenvío, no suma.

    EventChunkResponseDto response = sendVideo(4);

    assertEquals("CONTINUE", response.getFinalVerdict());
    assertEquals("LLAMATIVO", response.getFinalSeverity());
    assertEquals(
        RuleBasedDangerEvaluator.VISIT_PRESENCE_MESSAGE + " · Persona reconocida",
        response.getMessage());
    assertEquals(TrackStatus.CREATED, storedTrack.getStatus());
    assertFalse(storedTrack.getAccumulatedSignals().contains("LOITERING"));
    assertFalse(storedTrack.getAccumulatedSignals().contains("PROLONGED_PRESENCE"));
    assertResponseContract(response);
    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    assertEquals(TrackAppCode.KNOWN_PERSON, mapper.toDto(storedTrack).getCode());
    assertEquals(response.getMessage(), mapper.toDetailDto(storedTrack, null).getTitle());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void newPresenceSignalDoesNotRequireMatchingPeopleBetweenClips() throws Exception {
    for (int number = 1; number <= 4; number++) {
      when(videoAnalyzer.analyze(PAYLOAD)).thenReturn(stationaryClip(5, number * 100));
      EventChunkResponseDto response = sendVideo(number);
      assertEquals(number == 4 ? "LLAMATIVO" : "NORMAL", response.getFinalSeverity());
      assertEquals("CONTINUE", response.getFinalVerdict());
      assertResponseContract(response);
    }
    assertTrue(
        storedTrack.getAccumulatedSignals().contains(Signal.PROLONGED_VISIT_PRESENCE.name()));
    assertEquals(4, records.size());
    assertTrue(records.stream().allMatch(record -> record.getVideoPresenceSeconds() == 5.0));
    TrackMapper mapper = new TrackMapper(new TrackMessageBuilder(evaluator));
    assertEquals(TrackAppCode.ACTIVITY, mapper.toDto(storedTrack).getCode());
    assertEquals(
        RuleBasedDangerEvaluator.VISIT_PRESENCE_MESSAGE,
        mapper.toDetailDto(storedTrack, null).getTitle());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "failed", "insufficient"})
  void absentOrUnreliableClipInterruptsAccumulation(String kind) {
    VideoAnalysisResult valid = stationaryClip(5, 71);
    when(videoAnalyzer.analyze(PAYLOAD)).thenReturn(valid);
    sendVideo(1);
    sendVideo(2);
    if (kind.equals("failed")) {
      when(videoAnalyzer.analyze(PAYLOAD)).thenThrow(new IllegalStateException("ML unavailable"));
    } else {
      when(videoAnalyzer.analyze(PAYLOAD))
          .thenReturn(
              kind.equals("empty")
                  ? new VideoAnalysisResult(valid.metadata(), List.of())
                  : new VideoAnalysisResult(valid.metadata(), valid.observations(), 11));
    }
    sendVideo(3);
    // doReturn also replaces the throwing stub.
    org.mockito.Mockito.doReturn(valid).when(videoAnalyzer).analyze(PAYLOAD);
    sendVideo(4);
    EventChunkResponseDto response = sendVideo(5);

    assertEquals("NORMAL", response.getFinalSeverity());
    assertFalse(
        storedTrack.getAccumulatedSignals().contains(Signal.PROLONGED_VISIT_PRESENCE.name()));
    assertEquals(5, records.size()); // También persiste la barrera de un análisis fallido.
    assertEquals(kind.equals("empty") ? 0.0 : null, records.get(2).getVideoPresenceSeconds());
  }

  @Test
  void missingClipDoesNotCountUntilItsLateArrivalAndAbsenceKeepsConfirmedHistory() {
    VideoAnalysisResult valid = stationaryClip(5, 71);
    when(videoAnalyzer.analyze(PAYLOAD)).thenReturn(valid);
    sendVideo(1);
    sendVideo(2);
    sendVideo(4);
    assertEquals("NORMAL", sendVideo(5).getFinalSeverity());
    assertEquals("LLAMATIVO", sendVideo(3).getFinalSeverity());
    when(videoAnalyzer.analyze(PAYLOAD))
        .thenReturn(new VideoAnalysisResult(valid.metadata(), List.of()));
    assertEquals("LLAMATIVO", sendVideo(6).getFinalSeverity());
    when(conversationAnalyzer.analyze(PAYLOAD))
        .thenReturn(new ConversationAnalysisResult("", List.of()));
    assertEquals("LLAMATIVO", send(ChunkType.AUDIO).getFinalSeverity());
  }

  private EventChunkResponseDto sendVideo(int number) {
    return service.handleEvent(
        TRACK_ID.toString(),
        DEVICE_ID.toString(),
        ChunkType.VIDEO,
        PAYLOAD,
        "chunk_%03d.mp4".formatted(number));
  }

  private VideoAnalysisResult stationaryClip(double duration, long personId) {
    return new VideoAnalysisResult(
        new VideoMetadata(1920, 1080, 2, duration, (int) (duration * 2)),
        IntStream.range(0, (int) (duration * 2))
            .mapToObj(
                frame ->
                    new VideoObservation(
                        frame,
                        frame / 2.0,
                        personId,
                        0.95,
                        40,
                        30,
                        60,
                        70,
                        50,
                        50,
                        50,
                        50,
                        50,
                        70,
                        10,
                        0.1))
            .toList());
  }

  private void coveredFaceImage() {
    when(faceRecognizer.recognize(PAYLOAD, DEVICE_ID))
        .thenReturn(FaceRecognitionOutcome.known("Juan Pérez"));
    when(objectRecognizer.detectObjects(PAYLOAD))
        .thenReturn(
            new ObjectDetectionResult(
                List.of(
                    new DetectedObject("person", 0.95), new DetectedObject("covered_face", 0.95)),
                Map.of("person", 1, "covered_face", 1)));
  }

  private void contextObjectsImage() {
    when(objectRecognizer.detectObjects(PAYLOAD))
        .thenReturn(
            new ObjectDetectionResult(
                List.of(
                    new DetectedObject("person", 0.95),
                    new DetectedObject("ladder", 0.95),
                    new DetectedObject("Tool", 0.95),
                    new DetectedObject("package", 0.95)),
                Map.of("person", 1, "ladder", 1, "Tool", 1, "package", 1)));
  }

  private void fastApproachVideo() {
    List<VideoObservation> observations =
        IntStream.rangeClosed(0, 12)
            .mapToObj(
                sample -> {
                  double timestamp = sample * 0.5;
                  double area = 5 * Math.exp(0.16 * timestamp);
                  return new VideoObservation(
                      sample,
                      timestamp,
                      71,
                      0.95,
                      40,
                      20,
                      60,
                      90,
                      50,
                      45 + timestamp,
                      50,
                      45 + timestamp,
                      50,
                      90,
                      area,
                      4.0);
                })
            .toList();
    when(videoAnalyzer.analyze(PAYLOAD))
        .thenReturn(
            new VideoAnalysisResult(new VideoMetadata(1920, 1080, 2, 6.5, 13), observations));
  }

  private void recognizedImageWithoutThreats() {
    when(faceRecognizer.recognize(PAYLOAD, DEVICE_ID))
        .thenReturn(FaceRecognitionOutcome.known("Juan Pérez"));
    when(objectRecognizer.detectObjects(PAYLOAD))
        .thenReturn(
            new ObjectDetectionResult(
                List.of(new DetectedObject("person", 0.95)), Map.of("person", 1)));
  }

  private EventChunkResponseDto send(ChunkType type) {
    return service.handleEvent(TRACK_ID.toString(), DEVICE_ID.toString(), type, PAYLOAD);
  }

  private void assertResponseContract(EventChunkResponseDto response) throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode json =
        objectMapper.readTree(objectMapper.writeValueAsString(StandardResponse.of(response)));
    assertEquals(Set.of("data"), fieldNames(json));
    JsonNode data = json.get("data");
    assertEquals(RESPONSE_FIELDS, fieldNames(data));
    assertEquals(TRACK_ID.toString(), data.get("trackId").asText());
    for (String field :
        List.of("trackId", "chunkType", "finalVerdict", "finalSeverity", "message")) {
      assertTrue(data.get(field).isTextual(), field);
    }
    if (response.getAdditionalInformation() == null) {
      assertTrue(data.get("additionalInformation").isNull());
    } else {
      assertEquals(Set.of("personName"), fieldNames(data.get("additionalInformation")));
    }
  }

  private Set<String> fieldNames(JsonNode node) {
    Set<String> fields = new HashSet<>();
    node.fieldNames().forEachRemaining(fields::add);
    return fields;
  }
}
