package com.is2.detective_manager.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.NotificationPublisher;
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
import com.is2.detective_manager.orchestrator.step.facerecognition.FaceRecognitionStep;
import com.is2.detective_manager.service.PersonService;
import com.is2.detective_manager.service.TrackConsolidation;
import com.is2.detective_manager.service.TrackPersistenceService;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    when(pipeline.stepsFor(ChunkType.IMAGE)).thenReturn(List.of(StubStep.failing()));
    when(trackPersistenceService.consolidateTrack(any(), any(), anyBoolean()))
        .thenReturn(consolidation("Sin alertas", Verdict.CONTINUE, false));

    EventChunkResponseDto response =
        service.handleEvent(TRACK_ID, DEVICE_ID, ChunkType.IMAGE, IMAGE);

    assertEquals("Sin alertas", response.getMessage());
    assertNull(response.getAdditionalInformation());
    verify(personService, never()).saveOrphanEmbeddingIfPossible(any(), any(), any());
    verify(trackPersistenceService, never())
        .saveAssessment(any(), any(), any(), any(), anyLong(), any(), any());
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
    private final RuntimeException error;

    private StubStep(StepResult result) {
      this(result, null);
    }

    private StubStep(StepResult result, RuntimeException error) {
      this.result = result;
      this.error = error;
    }

    private static StubStep failing() {
      return new StubStep(null, new IllegalStateException("face step failed"));
    }

    @Override
    public boolean supports(Set<ChunkType> availableInputs) {
      return true;
    }

    @Override
    public StepResult execute(StepContext context) {
      if (error != null) {
        throw error;
      }
      return result;
    }
  }
}
