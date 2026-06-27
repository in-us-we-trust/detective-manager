package com.is2.detective_manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.model.dto.FaceRecognitionClosestMatchDto;
import com.is2.detective_manager.model.dto.FaceRecognitionResultDto;
import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
import com.is2.detective_manager.repository.DeviceRepository;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
import com.is2.detective_manager.repository.PersonRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class DevicesControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private DbTestUtils dbUtils;
  @Autowired private DeviceRepository deviceRepository;
  @Autowired private FaceEmbeddingRepository faceEmbeddingRepository;
  @Autowired private PersonRepository personRepository;

  @MockitoBean private MlServiceApiClient mlServiceApiClient;
  @MockitoBean private NotificationPublisher notificationPublisher;
  @MockitoBean private CloudinaryStorageClient cloudinaryStorageClient;

  private static final String DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000";
  private static final String SECOND_DEVICE_ID = "223e4567-e89b-12d3-a456-426614174000";
  private static final String MOCK_IMAGE_URL =
      "https://firebasestorage.googleapis.com/v0/b/test-bucket/o/test.jpg?alt=media";
  private static final String VALID_BASE64_IMAGE = "dGVzdGltYWdl";
  private static final String LAST_BOOT = "2024-01-01T10:00:00Z";
  private static final String LAST_SEEN = "2024-01-01T11:00:00Z";

  @BeforeEach
  void setUp() {
    dbUtils.clearTables();
    when(cloudinaryStorageClient.uploadEventImage(anyString(), any())).thenReturn(MOCK_IMAGE_URL);
  }

  @AfterEach
  void tearDown() {
    dbUtils.clearTables();
  }

  @Test
  void createDevice_validRequest_returnsCreatedAndPersistsDevice() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices")
                .contentType("application/json")
                .content(
                    """
                    {
                      "id": "%s",
                      "serialNumber": "SN-001",
                      "city": "San Miguel",
                      "lastSeen": "%s",
                      "lastBoot": "%s",
                      "detectionThreshold": 80,
                      "location": "Front door",
                      "firmwareVersion": "1.0.0"
                    }
                    """
                        .formatted(DEVICE_ID, LAST_SEEN, LAST_BOOT)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.serialNumber").value("SN-001"))
        .andExpect(jsonPath("$.data.city").value("San Miguel"))
        .andExpect(jsonPath("$.data.status").value("UNCLAIMED"))
        .andExpect(jsonPath("$.data.uptime").value(3600))
        .andExpect(jsonPath("$.data.detectionThreshold").value(80))
        .andExpect(jsonPath("$.data.location").value("Front door"))
        .andExpect(jsonPath("$.data.firmwareVersion").value("1.0.0"));

    assertEquals(1, deviceRepository.count());
  }

  @Test
  void createDevice_detectionThresholdOmitted_defaultsToNinety() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices")
                .contentType("application/json")
                .content(
                    """
                    {
                      "id": "%s",
                      "serialNumber": "SN-002",
                      "lastSeen": "%s",
                      "lastBoot": "%s"
                    }
                    """
                        .formatted(DEVICE_ID, LAST_SEEN, LAST_BOOT)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.detectionThreshold").value(90));
  }

  @Test
  void createDevice_cityOmitted_persistsWithNullCity() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices")
                .contentType("application/json")
                .content(
                    """
                    {
                      "id": "%s",
                      "serialNumber": "SN-003",
                      "lastSeen": "%s",
                      "lastBoot": "%s"
                    }
                    """
                        .formatted(DEVICE_ID, LAST_SEEN, LAST_BOOT)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.city").doesNotExist());
  }

  @Test
  void createDevice_statusSentInBody_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices")
                .contentType("application/json")
                .content(
                    """
                    {
                      "id": "%s",
                      "serialNumber": "SN-004",
                      "lastSeen": "%s",
                      "lastBoot": "%s",
                      "status": "ONLINE"
                    }
                    """
                        .formatted(DEVICE_ID, LAST_SEEN, LAST_BOOT)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"));
  }

  @Test
  void createDevice_lastSeenBeforeLastBoot_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices")
                .contentType("application/json")
                .content(
                    """
                    {
                      "id": "%s",
                      "serialNumber": "SN-005",
                      "lastSeen": "2024-01-01T09:00:00Z",
                      "lastBoot": "2024-01-01T10:00:00Z"
                    }
                    """
                        .formatted(DEVICE_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid timestamps."));
  }

  @Test
  void createDevice_duplicateDeviceId_returnsConflict() throws Exception {
    String body =
        """
        {
          "id": "%s",
          "serialNumber": "SN-006",
          "lastSeen": "%s",
          "lastBoot": "%s"
        }
        """
            .formatted(DEVICE_ID, LAST_SEEN, LAST_BOOT);

    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices").contentType("application/json").content(body))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices").contentType("application/json").content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Device already exists."));
  }

  @Test
  void getDevice_existingDevice_returnsOk() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.serialNumber").value("SN-001"))
        .andExpect(jsonPath("$.data.status").value("UNCLAIMED"))
        .andExpect(jsonPath("$.data.uptime").value(3600));
  }

  @Test
  void getDevice_nonExistingDevice_returnsNotFound() throws Exception {
    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Device not found."));
  }

  @Test
  void getDevice_invalidUuid_returnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}", "invalid-uuid"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateDevice_location_returnsOkWithUpdatedLocation() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(
            patch("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                    { "location": "Back door" }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.location").value("Back door"))
        .andExpect(jsonPath("$.data.status").value("UNCLAIMED"));
  }

  @Test
  void updateDevice_firmwareVersion_returnsOkWithUpdatedFirmwareVersion() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(
            patch("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                    { "firmwareVersion": "2.0.0" }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.firmwareVersion").value("2.0.0"));
  }

  @Test
  void updateDevice_lastSeen_recalculatesUptime() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(
            patch("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                    { "lastSeen": "2024-01-01T13:00:00Z" }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.uptime").value(10800));
  }

  @Test
  void updateDevice_detectionThreshold_returnsOkWithUpdatedThreshold() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(
            patch("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                    { "detectionThreshold": 75 }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.detectionThreshold").value(75));
  }

  @Test
  void updateDevice_detectionThresholdNull_keepsExistingValue() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(
            patch("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                    { "detectionThreshold": null }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.detectionThreshold").value(90));
  }

  @Test
  void updateDevice_uptimeSentInBody_returnsBadRequest() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(
            patch("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID)
                .contentType("application/json")
                .content("""
                    { "uptime": 9999 }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"));
  }

  @Test
  void updateDevice_nonExistingDevice_returnsNotFound() throws Exception {
    mockMvc
        .perform(
            patch("/api/detective-manager/v1/devices/{deviceId}", UUID.randomUUID())
                .contentType("application/json")
                .content("""
                    { "location": "Garage" }
                    """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Device not found."));
  }

  @Test
  void claimDevice_unclaimedDevice_returnsOkWithClaimedStatus() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(post("/api/detective-manager/v1/devices/{deviceId}/claim", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.status").value("CLAIMED"));
  }

  @Test
  void claimDevice_alreadyClaimedDevice_returnsConflict() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(post("/api/detective-manager/v1/devices/{deviceId}/claim", DEVICE_ID))
        .andExpect(status().isOk());

    mockMvc
        .perform(post("/api/detective-manager/v1/devices/{deviceId}/claim", DEVICE_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Device cannot be claimed."));
  }

  @Test
  void unclaimDevice_claimedDevice_returnsOkWithUnclaimedStatus() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(post("/api/detective-manager/v1/devices/{deviceId}/claim", DEVICE_ID))
        .andExpect(status().isOk());

    mockMvc
        .perform(post("/api/detective-manager/v1/devices/{deviceId}/unclaim", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.status").value("UNCLAIMED"));
  }

  @Test
  void unclaimDevice_notClaimedDevice_returnsConflict() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(post("/api/detective-manager/v1/devices/{deviceId}/unclaim", DEVICE_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Device cannot be unclaimed."));
  }

  @Test
  void claimDevice_nonExistingDevice_returnsNotFound() throws Exception {
    mockMvc
        .perform(post("/api/detective-manager/v1/devices/{deviceId}/claim", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Device not found."));
  }

  @Test
  void unclaimDevice_nonExistingDevice_returnsNotFound() throws Exception {
    mockMvc
        .perform(post("/api/detective-manager/v1/devices/{deviceId}/unclaim", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Device not found."));
  }

  @Test
  void getDeviceEvents_existingDeviceWithEvents_returnsOk() throws Exception {
    createEvent(DEVICE_ID, "motion");

    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}/events", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data[0].eventType").value("motion"));
  }

  @Test
  void getDeviceEvents_existingDeviceWithoutEvents_returnsEmptyList() throws Exception {
    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}/events", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void getDeviceEvents_filtersOnlyRequestedDeviceEvents() throws Exception {
    createEvent(DEVICE_ID, "motion");
    createEvent(SECOND_DEVICE_ID, "bell");

    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}/events", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data[0].eventType").value("motion"));
  }

  @Test
  void getDeviceEvents_invalidDeviceId_returnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}/events", "invalid-uuid"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createFaceEmbedding_validRequest_returnsCreatedAndPersistsEmbedding() throws Exception {
    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenReturn(
            new GeneratedFaceEmbeddingDto(List.of(0.12, -0.34, 0.56), "ArcFace", "retinaface"));

    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices/{deviceId}/face-embeddings", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                    {
                      "personName": "Mateo",
                      "image": "%s"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").exists())
        .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.personId").value(1))
        .andExpect(jsonPath("$.data.personName").value("Mateo"))
        .andExpect(jsonPath("$.data.embedding.length()").value(3))
        .andExpect(jsonPath("$.data.modelName").value("ArcFace"))
        .andExpect(jsonPath("$.data.detectorBackend").value("retinaface"))
        .andExpect(jsonPath("$.data.dimensions").value(3))
        .andExpect(jsonPath("$.data.createdAt").exists());

    var savedEmbedding = faceEmbeddingRepository.findAll().get(0);
    assertEquals(List.of(0.12, -0.34, 0.56), savedEmbedding.getEmbedding());
    assertEquals(1, personRepository.count());

    verify(mlServiceApiClient)
        .generateFaceEmbedding(
            org.mockito.ArgumentMatchers.argThat(
                request -> VALID_BASE64_IMAGE.equals(request.getImage())));
  }

  @Test
  void createFaceEmbedding_missingPersonName_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices/{deviceId}/face-embeddings", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                    {
                      "image": "%s"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"));
  }

  @Test
  void createFaceEmbedding_invalidMlResponse_returnsBadGateway() throws Exception {
    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenReturn(new GeneratedFaceEmbeddingDto(List.of(), "ArcFace", "retinaface"));

    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices/{deviceId}/face-embeddings", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                    {
                      "personName": "Mateo",
                      "image": "%s"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("ML service error"));
  }

  @Test
  void reportEvent_callsFaceRecognitionWithKnownDeviceEmbeddings() throws Exception {
    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenReturn(
            new GeneratedFaceEmbeddingDto(List.of(0.1, 0.2), "ArcFace", "retinaface"),
            new GeneratedFaceEmbeddingDto(List.of(0.3, 0.4), "ArcFace", "retinaface"),
            new GeneratedFaceEmbeddingDto(List.of(0.9, 1.0), "ArcFace", "retinaface"));
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(
            FaceRecognitionResultDto.builder()
                .known(false)
                .threshold(0.4)
                .distance(0.61)
                .distanceMetric("cosine")
                .closestMatch(
                    FaceRecognitionClosestMatchDto.builder()
                        .embeddingId(1L)
                        .personId(1L)
                        .personName("Mateo")
                        .build())
                .modelName("ArcFace")
                .detectorBackend("retinaface")
                .build());

    createFaceEmbedding(DEVICE_ID, "Mateo");
    createFaceEmbedding(DEVICE_ID, "Another person");
    createFaceEmbedding(SECOND_DEVICE_ID, "Other device person");

    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices/{deviceId}/events", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                    {
                      "image": "%s"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.isIntruder").value(true))
        .andExpect(jsonPath("$.data.eventType").value("INTRUDER"))
        .andExpect(jsonPath("$.data.faceRecognition.known").value(false))
        .andExpect(jsonPath("$.data.faceRecognition.threshold").value(0.4))
        .andExpect(jsonPath("$.data.faceRecognition.distance").value(0.61))
        .andExpect(jsonPath("$.data.faceRecognition.distance_metric").value("cosine"))
        .andExpect(jsonPath("$.data.faceRecognition.closest_match.person_name").value("Mateo"))
        .andExpect(jsonPath("$.data.faceRecognition.model_name").value("ArcFace"))
        .andExpect(jsonPath("$.data.faceRecognition.detector_backend").value("retinaface"));

    verify(mlServiceApiClient, never()).analyzeIntruder(any());
    verify(mlServiceApiClient)
        .recognizeFace(
            org.mockito.ArgumentMatchers.argThat(
                request ->
                    VALID_BASE64_IMAGE.equals(request.getImage())
                        && request.getEmbeddings().size() == 2
                        && request.getEmbeddings().stream()
                            .allMatch(
                                embedding -> DEVICE_ID.equals(embedding.getDeviceId().toString()))
                        && request.getEmbeddings().stream()
                            .noneMatch(
                                embedding ->
                                    embedding.getPersonName().equals("Other device person"))));
    verify(notificationPublisher)
        .publishIntruderDetected(
            org.mockito.ArgumentMatchers.argThat(
                message ->
                    DEVICE_ID.equals(message.getDeviceId().toString())
                        && MOCK_IMAGE_URL.equals(message.getImageUrl())
                        && "high".equals(message.getSeverity())
                        && "Intruder detected".equals(message.getTitle())
                        && "An unknown person was detected.".equals(message.getMessage())
                        && message.getTime() != null
                        && message.getTime().endsWith("Z")
                        && message.getTags().contains("doorbell")));
  }

  @Test
  void getDeviceFaceEmbeddings_returnsAllEmbeddingsOnlyForRequestedDevice() throws Exception {
    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenReturn(
            new GeneratedFaceEmbeddingDto(List.of(0.1, 0.2), "ArcFace", "retinaface"),
            new GeneratedFaceEmbeddingDto(List.of(0.3, 0.4), "ArcFace", "retinaface"),
            new GeneratedFaceEmbeddingDto(List.of(0.9, 1.0), "ArcFace", "retinaface"));

    Long firstPersonId = createFaceEmbedding(DEVICE_ID, "Mateo");
    Long secondPersonId = createFaceEmbedding(DEVICE_ID, "Another person");
    createFaceEmbedding(SECOND_DEVICE_ID, "Other device person");

    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}/face-embeddings", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data[0].personId").value(firstPersonId))
        .andExpect(jsonPath("$.data[0].personName").value("Mateo"))
        .andExpect(jsonPath("$.data[0].embedding[0]").value(0.1))
        .andExpect(jsonPath("$.data[1].deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data[1].personId").value(secondPersonId))
        .andExpect(jsonPath("$.data[1].personName").value("Another person"))
        .andExpect(jsonPath("$.data[1].embedding[0]").value(0.3));
  }

  @Test
  void getFaceEmbeddings_returnsOnlyRequestedPersonEmbeddings() throws Exception {
    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenReturn(
            new GeneratedFaceEmbeddingDto(List.of(0.1, 0.2), "ArcFace", "retinaface"),
            new GeneratedFaceEmbeddingDto(List.of(0.3, 0.4), "ArcFace", "retinaface"),
            new GeneratedFaceEmbeddingDto(List.of(0.9, 1.0), "ArcFace", "retinaface"));

    Long personId = createFaceEmbedding("Mateo");
    Long samePersonId = createFaceEmbedding(" mateo ");
    createFaceEmbedding("Another person");

    mockMvc
        .perform(
            get(
                "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}/face-embeddings",
                DEVICE_ID,
                personId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data[0].personId").value(personId))
        .andExpect(jsonPath("$.data[0].personName").value("Mateo"))
        .andExpect(jsonPath("$.data[0].embedding[0]").value(0.1))
        .andExpect(jsonPath("$.data[0].modelName").value("ArcFace"))
        .andExpect(jsonPath("$.data[1].personId").value(personId))
        .andExpect(jsonPath("$.data[1].embedding[0]").value(0.3));

    assertEquals(personId, samePersonId);
    assertEquals(2, personRepository.count());
  }

  @Test
  void getFaceEmbeddings_invalidPersonId_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            get(
                "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}/face-embeddings",
                DEVICE_ID,
                "invalid-id"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deleteDevice_existingDevice_returnsNoContent() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(delete("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteDevice_existingDevice_subsequentGetReturnsNotFound() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(delete("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteDevice_existingDevice_subsequentPatchReturnsNotFound() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(delete("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            patch("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID)
                .contentType("application/json")
                .content("""
                    { "location": "Garage" }
                    """))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteDevice_alreadyDeletedDevice_returnsNotFound() throws Exception {
    createDevice(DEVICE_ID);

    mockMvc
        .perform(delete("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(delete("/api/detective-manager/v1/devices/{deviceId}", DEVICE_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Device not found."));
  }

  private void createDevice(String deviceId) throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices")
                .contentType("application/json")
                .content(
                    """
                    {
                      "id": "%s",
                      "serialNumber": "SN-001",
                      "lastSeen": "%s",
                      "lastBoot": "%s"
                    }
                    """
                        .formatted(deviceId, LAST_SEEN, LAST_BOOT)))
        .andExpect(status().isCreated());
  }

  private void createEvent(String deviceId, String eventType) throws Exception {
    when(mlServiceApiClient.recognizeFace(any()))
        .thenReturn(FaceRecognitionResultDto.builder().known(true).build());

    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", deviceId)
                .content(
                    """
                    {
                      "image": "%s",
                      "type": "%s"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE, eventType)))
        .andExpect(status().isCreated());
  }

  private Long createFaceEmbedding(String personName) throws Exception {
    return createFaceEmbedding(DEVICE_ID, personName);
  }

  private Long createFaceEmbedding(String deviceId, String personName) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/detective-manager/v1/devices/{deviceId}/face-embeddings", deviceId)
                    .contentType("application/json")
                    .content(
                        """
                        {
                          "personName": "%s",
                          "image": "%s"
                        }
                        """
                            .formatted(personName, VALID_BASE64_IMAGE)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.personId")).longValue();
  }
}
