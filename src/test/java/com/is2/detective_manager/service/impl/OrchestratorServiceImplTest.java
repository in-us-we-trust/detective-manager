package com.is2.detective_manager.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.model.dto.EventChunkResponseDto;
import com.is2.detective_manager.model.dto.IntruderNotificationMessageDto;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.AnalysisPipeline;
import com.is2.detective_manager.orchestrator.AnalysisStep;
import com.is2.detective_manager.orchestrator.StepContext;
import com.is2.detective_manager.orchestrator.StepResult;
import com.is2.detective_manager.orchestrator.Verdict;
import com.is2.detective_manager.orchestrator.evaluator.Assessment;
import com.is2.detective_manager.orchestrator.evaluator.AssessmentStatus;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalysisResult;
import com.is2.detective_manager.orchestrator.step.conversationanalysis.ConversationAnalysisStep;
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionStep;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisStep;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoFeatureCalculator;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoPresenceChunk;
import com.is2.detective_manager.service.PersonService;
import com.is2.detective_manager.service.TrackConsolidation;
import com.is2.detective_manager.service.TrackPersistenceService;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceImplTest {

  private static final String TRACK_ID = "123e4567-e89b-12d3-a456-426614174000";
  private static final String DEVICE_ID = "123e4567-e89b-12d3-a456-426614174001";
  private static final UUID DEVICE_UUID = UUID.fromString(DEVICE_ID);
  private static final byte[] IMAGE = "image-bytes".getBytes();
  private static final String IMAGE_URL = "https://cdn.example/image.jpg";

  @Mock private AnalysisPipeline pipeline;
  @Mock private TrackPersistenceService trackPersistenceService;
  @Mock private NotificationPublisher notificationPublisher;
  @Mock private CloudinaryStorageClient cloudinaryStorageClient;
  @Mock private PersonService personService;

  private OrchestratorServiceImpl service;

  @BeforeEach
  void setUp() {
    service =
        new OrchestratorServiceImpl(
            pipeline,
            trackPersistenceService,
            notificationPublisher,
            cloudinaryStorageClient,
            personService,
            directExecutor());
    when(trackPersistenceService.findOrCreateTrack(any(), any())).thenReturn(new Track());
  }

  @Test
  void unknownPerson_persistsSuggestionEmbeddingAndKeepsExistingMessage() {
    when(cloudinaryStorageClient.uploadImageBytes(IMAGE, DEVICE_UUID)).thenReturn(IMAGE_URL);
    when(pipeline.stepsFor(ChunkType.IMAGE))
        .thenReturn(List.of(new StubStep(unknownPersonResult())));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("No se reconoce a la persona", Verdict.CONTINUE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    verify(personService)
        .saveOrphanEmbeddingIfPossible(
            DEVICE_UUID, Base64.getEncoder().encodeToString(IMAGE), IMAGE_URL);
    assertEquals("No se reconoce a la persona", response.getMessage());
    assertNull(response.getAdditionalInformation());
    assertEquals("IMAGE", response.getChunkType());
  }

  @Test
  void unknownPerson_stillPersistsSuggestionWhenImageUploadFails() {
    when(cloudinaryStorageClient.uploadImageBytes(IMAGE, DEVICE_UUID))
        .thenThrow(new RuntimeException("cloudinary down"));
    when(pipeline.stepsFor(ChunkType.IMAGE))
        .thenReturn(List.of(new StubStep(unknownPersonResult())));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("No se reconoce a la persona", Verdict.CONTINUE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    verify(personService)
        .saveOrphanEmbeddingIfPossible(
            DEVICE_UUID, Base64.getEncoder().encodeToString(IMAGE), null);
    assertEquals("No se reconoce a la persona", response.getMessage());
  }

  @Test
  void knownPerson_addsNameToMessageAndImageAdditionalInformation() {
    when(cloudinaryStorageClient.uploadImageBytes(IMAGE, DEVICE_UUID)).thenReturn(IMAGE_URL);
    when(pipeline.stepsFor(ChunkType.IMAGE)).thenReturn(List.of(new StubStep(knownPersonResult())));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("Persona reconocida", Verdict.SAFE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    assertEquals("Persona reconocida: Juan Pérez", response.getMessage());
    assertEquals("Juan Pérez", response.getAdditionalInformation().get("personName"));
    verify(personService, never()).saveOrphanEmbeddingIfPossible(any(), any(), any());
  }

  @ParameterizedTest
  @MethodSource("unusablePersonNames")
  void unusableName_preservesTheConclusionWithoutAddingEmptyPersonalInformation(
      Object name, Verdict verdict, String expectedMessage) {
    Map<String, Object> data = new HashMap<>();
    data.put(FaceRecognitionStep.PERSON_NAME_DATA_KEY, name);
    when(pipeline.stepsFor(ChunkType.IMAGE))
        .thenReturn(
            List.of(
                new StubStep(StepResult.builder().signal(Signal.KNOWN_PERSON).data(data).build())));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation(expectedMessage, verdict, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    assertEquals(expectedMessage, response.getMessage());
    assertEquals(verdict.name(), response.getFinalVerdict());
    assertEquals(verdict == Verdict.DANGER ? "PELIGROSO" : "NORMAL", response.getFinalSeverity());
    assertNull(response.getAdditionalInformation());
  }

  @ParameterizedTest
  @MethodSource("usablePersonNames")
  void usableName_isCleanAndDoesNotBreakTheAlertOrDuplicatePunctuation(
      String receivedName, String alert, String expectedName, String expectedMessage) {
    when(pipeline.stepsFor(ChunkType.IMAGE))
        .thenReturn(
            List.of(
                new StubStep(
                    StepResult.builder()
                        .signal(Signal.KNOWN_PERSON)
                        .signal(Signal.WEAPON_DETECTED)
                        .data(Map.of(FaceRecognitionStep.PERSON_NAME_DATA_KEY, receivedName))
                        .build())));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation(alert, Verdict.DANGER, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    assertEquals(expectedMessage, response.getMessage());
    assertEquals(expectedName, response.getAdditionalInformation().get("personName"));
    assertEquals("DANGER", response.getFinalVerdict());
    assertEquals("PELIGROSO", response.getFinalSeverity());
  }

  @Test
  void nameWithoutKnownPersonSignal_isNotPresentedAsARecognizedPerson() {
    when(pipeline.stepsFor(ChunkType.IMAGE))
        .thenReturn(
            List.of(
                new StubStep(
                    StepResult.builder()
                        .signal(Signal.UNKNOWN_PERSON)
                        .data(Map.of(FaceRecognitionStep.PERSON_NAME_DATA_KEY, "Juan Pérez"))
                        .build())));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("No se reconoce a la persona", Verdict.CONTINUE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    assertEquals("No se reconoce a la persona", response.getMessage());
    assertNull(response.getAdditionalInformation());
  }

  @Test
  void knownPerson_includesNameInDangerNotificationWithoutDroppingTheAlert() {
    when(cloudinaryStorageClient.uploadImageBytes(IMAGE, DEVICE_UUID)).thenReturn(IMAGE_URL);
    when(pipeline.stepsFor(ChunkType.IMAGE)).thenReturn(List.of(new StubStep(knownPersonResult())));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("La persona está armada", Verdict.DANGER, true));
    when(trackPersistenceService.findMostSevereImageUrl(any())).thenReturn(Optional.of(IMAGE_URL));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    assertEquals("La persona está armada. Persona reconocida: Juan Pérez", response.getMessage());
    assertEquals("Juan Pérez", response.getAdditionalInformation().get("personName"));

    ArgumentCaptor<IntruderNotificationMessageDto> notification =
        ArgumentCaptor.forClass(IntruderNotificationMessageDto.class);
    verify(notificationPublisher).publishIntruderDetected(notification.capture());
    assertEquals(
        "La persona está armada. Persona reconocida: Juan Pérez",
        notification.getValue().getMessage());
  }

  @Test
  void videoChunk_doesNotPersistSuggestionOrAdditionalInformation() {
    when(pipeline.stepsFor(ChunkType.VIDEO))
        .thenReturn(
            List.of(
                new StubStep(
                    StepResult.builder().signal(Signal.LOITERING).data(Map.of()).build())));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("Posible comportamiento de merodeo", Verdict.CONTINUE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.VIDEO, "video".getBytes());

    assertEquals("Posible comportamiento de merodeo", response.getMessage());
    assertNull(response.getAdditionalInformation());
    verify(personService, never()).saveOrphanEmbeddingIfPossible(any(), any(), any());
    verify(cloudinaryStorageClient, never()).uploadImageBytes(any(), any());
  }

  @Test
  void failedFaceStep_doesNotPersistSuggestionAndStillReturnsAssessment() {
    when(cloudinaryStorageClient.uploadImageBytes(IMAGE, DEVICE_UUID)).thenReturn(IMAGE_URL);
    when(pipeline.stepsFor(ChunkType.IMAGE)).thenReturn(List.of(failingFaceStep()));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("Sin alertas", Verdict.CONTINUE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    assertEquals(
        "No se pudo completar el análisis de reconocimiento facial", response.getMessage());
    assertEquals("CONTINUE", response.getFinalVerdict());
    assertEquals("NORMAL", response.getFinalSeverity());
    assertNull(response.getAdditionalInformation());
    verify(personService, never()).saveOrphanEmbeddingIfPossible(any(), any(), any());
    verify(trackPersistenceService, never())
        .saveAssessment(any(), any(), any(), any(), anyLong(), any(), any());
  }

  @Test
  void failedAudioAfterSafe_keepsPreviousConclusionAndAddsSourceDiagnostic() {
    Track previousTrack = new Track();
    previousTrack.setAccumulatedSignals("KNOWN_PERSON,NO_THREAT_OBJECT");
    previousTrack.setFinalVerdict(Verdict.SAFE);
    previousTrack.setFinalSeverity(Severity.NORMAL);
    when(trackPersistenceService.findOrCreateTrack(any(), any())).thenReturn(previousTrack);
    ConversationAnalysisStep failedAudio =
        new ConversationAnalysisStep(
            ignored -> {
              throw new IllegalStateException("ML connection failed");
            });
    when(pipeline.stepsFor(ChunkType.AUDIO)).thenReturn(List.of(failedAudio));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("Persona reconocida", Verdict.SAFE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.AUDIO, new byte[] {1});

    assertEquals("SAFE", response.getFinalVerdict());
    assertEquals("NORMAL", response.getFinalSeverity());
    assertEquals(
        "Persona reconocida · No se pudo completar el análisis de audio", response.getMessage());
    verify(trackPersistenceService).consolidateTrack(UUID.fromString(TRACK_ID), List.of(), true);
    verify(trackPersistenceService, never())
        .saveAssessment(any(), any(), any(), any(), anyLong(), any(), any());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void insufficientAudio_returnsNeutralExplanationWithoutAddingReassuringSignals() {
    ConversationAnalysisStep insufficientAudio =
        new ConversationAnalysisStep(ignored -> new ConversationAnalysisResult(" ", List.of()));
    when(pipeline.stepsFor(ChunkType.AUDIO)).thenReturn(List.of(insufficientAudio));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("Sin alertas", Verdict.CONTINUE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.AUDIO, new byte[] {1});

    assertEquals("CONTINUE", response.getFinalVerdict());
    assertEquals("NORMAL", response.getFinalSeverity());
    assertEquals(
        "No se obtuvo suficiente información para evaluar la conversación.", response.getMessage());
    assertFalse(response.getMessage().contains("Sin alertas"));
    verify(trackPersistenceService).consolidateTrack(UUID.fromString(TRACK_ID), List.of(), true);
    verify(trackPersistenceService)
        .saveAssessment(
            any(),
            any(),
            eq("ConversationAnalysisStep"),
            eq(List.of()),
            anyLong(),
            eq(ChunkType.AUDIO),
            eq(null));
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void validVideoWithoutPersons_returnsNeutralMessageAndKeepsPublicResponseShape() {
    VideoAnalysisProperties properties = new VideoAnalysisProperties();
    VideoAnalysisStep emptyVideo =
        new VideoAnalysisStep(
            ignored ->
                new VideoAnalysisResult(
                    new VideoAnalysisResult.VideoMetadata(1920, 1080, 30, 5, 150), List.of()),
            new VideoFeatureCalculator(properties),
            properties);
    when(pipeline.stepsFor(ChunkType.VIDEO)).thenReturn(List.of(emptyVideo));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("Sin alertas", Verdict.CONTINUE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.VIDEO, new byte[] {1});

    assertEquals("No se detectaron personas en el video", response.getMessage());
    assertEquals("CONTINUE", response.getFinalVerdict());
    assertEquals("NORMAL", response.getFinalSeverity());
    ArgumentCaptor<VideoPresenceChunk> presence = ArgumentCaptor.forClass(VideoPresenceChunk.class);
    verify(trackPersistenceService).consolidateTrack(UUID.fromString(TRACK_ID), List.of(), true);
    verify(trackPersistenceService)
        .saveAssessment(
            any(),
            any(),
            eq("VideoAnalysisStep"),
            eq(List.of()),
            anyLong(),
            eq(ChunkType.VIDEO),
            eq(null),
            presence.capture());
    assertEquals(0, presence.getValue().observedSeconds());
    assertTrue(presence.getValue().key().startsWith("sha256:"));

    JsonNode json = new ObjectMapper().valueToTree(response);
    Set<String> fields = new HashSet<>();
    json.fieldNames().forEachRemaining(fields::add);
    assertEquals(
        Set.of(
            "trackId",
            "chunkType",
            "finalVerdict",
            "finalSeverity",
            "message",
            "additionalInformation"),
        fields);
    assertEquals("CONTINUE", json.get("finalVerdict").asText());
    assertEquals("NORMAL", json.get("finalSeverity").asText());
    assertTrue(json.get("additionalInformation").isNull());
    verify(notificationPublisher, never()).publishIntruderDetected(any());
  }

  @Test
  void failedFaceWithDetectedWeapon_preservesDangerEvidenceAndNotification() {
    when(cloudinaryStorageClient.uploadImageBytes(IMAGE, DEVICE_UUID)).thenReturn(IMAGE_URL);
    when(pipeline.stepsFor(ChunkType.IMAGE))
        .thenReturn(
            List.of(
                failingFaceStep(),
                new StubStep(StepResult.builder().signal(Signal.WEAPON_DETECTED).build())));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("La persona está armada", Verdict.DANGER, true));
    when(trackPersistenceService.findMostSevereImageUrl(any())).thenReturn(Optional.of(IMAGE_URL));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    String expectedMessage =
        "La persona está armada · No se pudo completar el análisis de reconocimiento facial";
    assertEquals("DANGER", response.getFinalVerdict());
    assertEquals("PELIGROSO", response.getFinalSeverity());
    assertEquals(expectedMessage, response.getMessage());
    verify(trackPersistenceService)
        .consolidateTrack(UUID.fromString(TRACK_ID), List.of(Signal.WEAPON_DETECTED), true);
    verify(trackPersistenceService)
        .saveAssessment(
            any(),
            any(),
            eq("StubStep"),
            eq(List.of(Signal.WEAPON_DETECTED)),
            anyLong(),
            eq(ChunkType.IMAGE),
            eq(IMAGE_URL));
    verify(personService, never()).saveOrphanEmbeddingIfPossible(any(), any(), any());
    ArgumentCaptor<IntruderNotificationMessageDto> notification =
        ArgumentCaptor.forClass(IntruderNotificationMessageDto.class);
    verify(notificationPublisher).publishIntruderDetected(notification.capture());
    assertEquals(expectedMessage, notification.getValue().getMessage());
    assertEquals(IMAGE_URL, notification.getValue().getImageUrl());
  }

  private static FaceRecognitionStep failingFaceStep() {
    return new FaceRecognitionStep(
        (image, deviceId) -> {
          throw new IllegalStateException("ML connection failed");
        });
  }

  private static StepResult unknownPersonResult() {
    return StepResult.builder()
        .signal(Signal.UNKNOWN_PERSON)
        .data(Map.of("faceKnown", false))
        .build();
  }

  private static StepResult knownPersonResult() {
    return StepResult.builder()
        .signal(Signal.KNOWN_PERSON)
        .data(Map.of(FaceRecognitionStep.PERSON_NAME_DATA_KEY, "Juan Pérez"))
        .build();
  }

  private static Stream<Arguments> unusablePersonNames() {
    return Stream.<Object>of(null, "", " \t\n ", "\u2003", 42)
        .flatMap(
            name ->
                Stream.of(
                    Arguments.of(name, Verdict.SAFE, "Persona reconocida"),
                    Arguments.of(name, Verdict.DANGER, "La persona está armada")));
  }

  private static Stream<Arguments> usablePersonNames() {
    return Stream.of(
        Arguments.of(
            " \tJuan Pérez\n",
            "La persona está armada.",
            "Juan Pérez",
            "La persona está armada. Persona reconocida: Juan Pérez"),
        Arguments.of(
            "La",
            "La persona está armada",
            "La",
            "La persona está armada. Persona reconocida: La"));
  }

  private static TrackConsolidation consolidation(
      String message, Verdict verdict, boolean shouldNotify) {
    Assessment assessment =
        Assessment.builder()
            .status(
                verdict == Verdict.CONTINUE
                    ? AssessmentStatus.EVALUATING
                    : AssessmentStatus.CONCLUDED)
            .severity(verdict == Verdict.DANGER ? Severity.PELIGROSO : Severity.NORMAL)
            .verdict(verdict)
            .message(message)
            .build();
    return new TrackConsolidation(assessment, shouldNotify);
  }

  private static ExecutorService directExecutor() {
    return new AbstractExecutorService() {
      private volatile boolean shutdown;

      @Override
      public void shutdown() {
        shutdown = true;
      }

      @Override
      public List<Runnable> shutdownNow() {
        shutdown = true;
        return List.of();
      }

      @Override
      public boolean isShutdown() {
        return shutdown;
      }

      @Override
      public boolean isTerminated() {
        return shutdown;
      }

      @Override
      public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
      }

      @Override
      public void execute(Runnable command) {
        command.run();
      }
    };
  }

  private static final class StubStep implements AnalysisStep {
    private final StepResult result;

    private StubStep(StepResult result) {
      this.result = result;
    }

    @Override
    public boolean supports(Set<ChunkType> availableInputs) {
      return true;
    }

    @Override
    public StepResult execute(StepContext context) {
      return result;
    }
  }
}
