package com.is2.detective_manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.model.dto.GeneratedFaceEmbeddingDto;
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
class PersonsControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private DbTestUtils dbUtils;

  @Autowired private PersonRepository personRepository;

  @Autowired private FaceEmbeddingRepository faceEmbeddingRepository;

  @MockitoBean private MlServiceApiClient mlServiceApiClient;

  @MockitoBean private CloudinaryStorageClient cloudinaryStorageClient;

  private static final String DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000";

  private static final String VALID_BASE64_IMAGE = "dGVzdGltYWdl";

  private static final String MOCK_IMAGE_URL =
      "https://firebasestorage.googleapis.com/v0/b/test-bucket/o/test.jpg?alt=media";

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
  void createPerson_validRequest_returnsCreated() throws Exception {
    mockValidEmbedding();

    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices/{deviceId}/persons", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                                        {
                                          "name": "Mateo",
                                          "images": ["%s"],
                                          "role": "Cuidador"
                                        }
                                        """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").exists())
        .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.name").value("Mateo"))
        .andExpect(jsonPath("$.data.role").value("Cuidador"))
        .andExpect(jsonPath("$.data.images.length()").value(1))
        .andExpect(jsonPath("$.data.images[0].id").exists());

    assertEquals(1, personRepository.count());
  }

  @Test
  void createPerson_multipleImages_returnsCreated() throws Exception {
    mockValidEmbedding();

    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices/{deviceId}/persons", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                                        {
                                          "name": "Juan Perez",
                                          "images": ["%s", "%s"]
                                        }
                                        """
                        .formatted(VALID_BASE64_IMAGE, VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").exists())
        .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.name").value("Juan Perez"))
        .andExpect(jsonPath("$.data.images.length()").value(2));

    assertEquals(1, personRepository.count());
    assertEquals(2, faceEmbeddingRepository.count());
  }

  @Test
  void createPerson_invalidMlResponse_returnsBadGateway() throws Exception {
    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenReturn(new GeneratedFaceEmbeddingDto(List.of(), "ArcFace", "retinaface"));

    mockMvc
        .perform(
            post("/api/detective-manager/v1/devices/{deviceId}/persons", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                                        {
                                          "name": "Mateo",
                                          "images": ["%s"]
                                        }
                                        """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("ML service error"));
  }

  @Test
  void getPersons_returnsPersons() throws Exception {
    mockValidEmbedding();
    createPerson(DEVICE_ID, "Mateo");
    createPerson(DEVICE_ID, "Juan Perez");

    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}/persons", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data[0].name").value("Mateo"))
        .andExpect(jsonPath("$.data[1].name").value("Juan Perez"));
  }

  @Test
  void getPersons_noPersons_returnsEmptyList() throws Exception {
    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}/persons", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void getPerson_existingPerson_returnsOk() throws Exception {
    mockValidEmbedding();
    Long personId = createPerson(DEVICE_ID, "Mateo");

    mockMvc
        .perform(
            get(
                "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}",
                DEVICE_ID,
                personId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(personId))
        .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.name").value("Mateo"))
        .andExpect(jsonPath("$.data.images.length()").value(1));
  }

  @Test
  void getPerson_personNotFound_returnsNotFound() throws Exception {
    mockMvc
        .perform(
            get("/api/detective-manager/v1/devices/{deviceId}/persons/{personId}", DEVICE_ID, 999))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Person not found."));
  }

  @Test
  void updatePerson_validRequest_returnsUpdatedPerson() throws Exception {
    mockValidEmbedding();
    Long personId = createPerson(DEVICE_ID, "Mateo");

    mockMvc
        .perform(
            patch(
                    "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}",
                    DEVICE_ID,
                    personId)
                .contentType("application/json")
                .content(
                    """
                                        {
                                          "name": "Mateo Updated",
                                          "role": "Guardaespaldas"
                                        }
                                        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(personId))
        .andExpect(jsonPath("$.data.name").value("Mateo Updated"))
        .andExpect(jsonPath("$.data.role").value("Guardaespaldas"))
        .andExpect(jsonPath("$.data.images.length()").value(1));
  }

  @Test
  void updatePerson_personNotFound_returnsNotFound() throws Exception {
    mockMvc
        .perform(
            patch("/api/detective-manager/v1/devices/{deviceId}/persons/{personId}", DEVICE_ID, 999)
                .contentType("application/json")
                .content(
                    """
                                        {
                                          "name": "Updated"
                                        }
                                        """))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Person not found."));
  }

  @Test
  void deletePerson_existingPerson_returnsNoContent() throws Exception {
    mockValidEmbedding();
    Long personId = createPerson(DEVICE_ID, "Mateo");

    mockMvc
        .perform(
            delete(
                "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}",
                DEVICE_ID,
                personId))
        .andExpect(status().isNoContent());

    assertEquals(0, personRepository.count());
    assertEquals(0, faceEmbeddingRepository.count());
  }

  @Test
  void deletePerson_personNotFound_returnsNotFound() throws Exception {
    mockMvc
        .perform(
            delete(
                "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}", DEVICE_ID, 999))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Person not found."));
  }

  @Test
  void addImages_validRequest_returnsUpdatedPerson() throws Exception {
    mockValidEmbedding();
    Long personId = createPerson(DEVICE_ID, "Mateo");

    mockMvc
        .perform(
            post(
                    "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}/images",
                    DEVICE_ID,
                    personId)
                .param("deviceId", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                                        {
                                          "images": ["%s", "%s"]
                                        }
                                        """
                        .formatted(VALID_BASE64_IMAGE, VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(personId))
        .andExpect(jsonPath("$.data.images.length()").value(3));

    assertEquals(3, faceEmbeddingRepository.count());
  }

  @Test
  void addImages_invalidEmbedding_returnsBadGateway() throws Exception {
    mockValidEmbedding();
    Long personId = createPerson(DEVICE_ID, "Mateo");

    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenReturn(new GeneratedFaceEmbeddingDto(List.of(), "ArcFace", "retinaface"));

    mockMvc
        .perform(
            post(
                    "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}/images",
                    DEVICE_ID,
                    personId)
                .param("deviceId", DEVICE_ID)
                .contentType("application/json")
                .content(
                    """
                                        {
                                          "images": ["%s"]
                                        }
                                        """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.title").value("ML service error"));
  }

  @Test
  void deleteImage_existingImage_returnsNoContent() throws Exception {
    mockValidEmbedding();
    Long personId = createPerson(DEVICE_ID, "Mateo");
    addImages(DEVICE_ID, personId);

    var embeddings =
        faceEmbeddingRepository.findAllByPerson_DeviceIdAndPerson_IdOrderByCreatedAtAsc(
            UUID.fromString(DEVICE_ID), personId);
    Long imageId = embeddings.get(0).getId();

    mockMvc
        .perform(
            delete(
                    "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}/images/{imageId}",
                    DEVICE_ID,
                    personId,
                    imageId)
                .param("deviceId", DEVICE_ID))
        .andExpect(status().isNoContent());

    assertEquals(1, faceEmbeddingRepository.count());
  }

  @Test
  void deleteImage_imageNotFound_returnsNotFound() throws Exception {
    mockValidEmbedding();
    Long personId = createPerson(DEVICE_ID, "Mateo");

    mockMvc
        .perform(
            delete(
                    "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}/images/{imageId}",
                    DEVICE_ID,
                    personId,
                    999)
                .param("deviceId", DEVICE_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Image not found"));
  }

  @Test
  void deleteImage_imageBelongsToAnotherPerson_returnsForbidden() throws Exception {
    mockValidEmbedding();
    Long personId1 = createPerson(DEVICE_ID, "Mateo");
    Long personId2 = createPerson(DEVICE_ID, "Juan");

    var embeddings =
        faceEmbeddingRepository.findAllByPerson_DeviceIdAndPerson_IdOrderByCreatedAtAsc(
            UUID.fromString(DEVICE_ID), personId1);
    Long imageId = embeddings.get(0).getId();

    mockMvc
        .perform(
            delete(
                    "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}/images/{imageId}",
                    DEVICE_ID,
                    personId2,
                    imageId)
                .param("deviceId", DEVICE_ID))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Invalid access"));
  }

  @Test
  void createFaceEmbedding_samePerson_reusesExistingPerson() throws Exception {
    mockValidEmbedding();

    Long firstPersonId = createPerson(DEVICE_ID, "Mateo");

    mockMvc
        .perform(get("/api/detective-manager/v1/devices/{deviceId}/persons", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));

    assertEquals(1, personRepository.count());
    assertEquals(1, faceEmbeddingRepository.count());
  }

  @Test
  void getFaceEmbeddings_returnsEmbeddings() throws Exception {
    mockValidEmbedding();
    Long personId = createPerson(DEVICE_ID, "Mateo");
    addImages(DEVICE_ID, personId);

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
        .andExpect(jsonPath("$.data[0].embedding.length()").value(3))
        .andExpect(jsonPath("$.data[0].modelName").value("ArcFace"))
        .andExpect(jsonPath("$.data[0].detectorBackend").value("retinaface"));
  }

  private void mockValidEmbedding() {
    when(mlServiceApiClient.generateFaceEmbedding(any()))
        .thenReturn(new GeneratedFaceEmbeddingDto(List.of(0.1, 0.2, 0.3), "ArcFace", "retinaface"));
  }

  private Long createPerson() throws Exception {
    return createPerson(DEVICE_ID, "Mateo");
  }

  private Long createPerson(String deviceId, String name) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/detective-manager/v1/devices/{deviceId}/persons", deviceId)
                    .contentType("application/json")
                    .content(
                        """
                                                {
                                                  "name": "%s",
                                                  "images": ["%s"]
                                                }
                                                """
                            .formatted(name, VALID_BASE64_IMAGE)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.id")).longValue();
  }

  private void addImages(Long personId) throws Exception {
    addImages(DEVICE_ID, personId);
  }

  private void addImages(String deviceId, Long personId) throws Exception {
    mockMvc
        .perform(
            post(
                    "/api/detective-manager/v1/devices/{deviceId}/persons/{personId}/images",
                    deviceId,
                    personId)
                .param("deviceId", deviceId)
                .contentType("application/json")
                .content(
                    """
                                        {
                                          "images": ["%s"]
                                        }
                                        """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated());
  }
}
