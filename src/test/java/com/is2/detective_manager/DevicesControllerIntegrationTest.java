package com.is2.detective_manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.client.UsersManagerApiClient;
import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
import com.is2.detective_manager.repository.PersonRepository;
import java.util.List;
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

  @Autowired private FaceEmbeddingRepository faceEmbeddingRepository;

  @Autowired private PersonRepository personRepository;

  @MockitoBean private MlServiceApiClient mlServiceApiClient;

  @MockitoBean private UsersManagerApiClient usersManagerApiClient;

  @MockitoBean private CloudinaryStorageClient cloudinaryStorageClient;

  private static final String DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000";

  private static final String SECOND_DEVICE_ID = "223e4567-e89b-12d3-a456-426614174000";

  private static final String MOCK_IMAGE_URL =
      "https://firebasestorage.googleapis.com/v0/b/test-bucket/o/test.jpg?alt=media";

  private static final String VALID_BASE64_IMAGE = "dGVzdGltYWdl";

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

  private void createEvent(String deviceId, String eventType) throws Exception {
    when(mlServiceApiClient.analyzeIntruder(any())).thenReturn(false);

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
