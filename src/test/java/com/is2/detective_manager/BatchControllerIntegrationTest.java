package com.is2.detective_manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.client.NotificationPublisher;
import com.is2.detective_manager.model.dto.ClusterDto;
import com.is2.detective_manager.model.entity.FaceEmbedding;
import com.is2.detective_manager.model.entity.FaceEmbeddingStatus;
import com.is2.detective_manager.model.entity.PersonStatus;
import com.is2.detective_manager.repository.FaceEmbeddingRepository;
import com.is2.detective_manager.repository.PersonRepository;
import java.time.LocalDateTime;
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
class BatchControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private DbTestUtils dbUtils;
  @Autowired private FaceEmbeddingRepository faceEmbeddingRepository;
  @Autowired private PersonRepository personRepository;

  @MockitoBean private MlServiceApiClient mlServiceApiClient;
  @MockitoBean private NotificationPublisher notificationPublisher;

  private static final String DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000";
  private static final UUID DEVICE_UUID = UUID.fromString(DEVICE_ID);
  private static final String MOCK_IMAGE_URL =
      "https://firebasestorage.googleapis.com/v0/b/test-bucket/o/test.jpg?alt=media";

  @BeforeEach
  void setUp() {
    dbUtils.clearTables();
  }

  @AfterEach
  void tearDown() {
    dbUtils.clearTables();
  }

  @Test
  void runBatch_noOrphanEmbeddings_returnsZeros() throws Exception {
    mockMvc
        .perform(post("/api/detective-manager/v1/batch/persons"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deletedOrphanEmbeddings").value(0))
        .andExpect(jsonPath("$.data.orphanEmbeddingsProcessed").value(0))
        .andExpect(jsonPath("$.data.unknownPersonsCreated").value(0));

    verify(mlServiceApiClient, never()).clusterEmbeddings(any());
  }

  @Test
  void runBatch_withOrphanEmbeddings_callsClusteringAndCreatesPersons() throws Exception {
    List<FaceEmbedding> orphans = saveOrphanEmbeddings(3, differentDays());

    ClusterDto cluster = new ClusterDto();
    cluster.setEmbeddingIds(orphans.stream().map(FaceEmbedding::getId).toList());

    when(mlServiceApiClient.clusterEmbeddings(any())).thenReturn(List.of(cluster));

    mockMvc
        .perform(post("/api/detective-manager/v1/batch/persons"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orphanEmbeddingsProcessed").value(3))
        .andExpect(jsonPath("$.data.unknownPersonsCreated").value(1));

    assertEquals(1, personRepository.count());
    assertEquals(PersonStatus.UNKNOWN, personRepository.findAll().get(0).getStatus());
    assertEquals(0, faceEmbeddingRepository.findAllByPersonIsNull().size());

    verify(notificationPublisher, times(1)).publishUnknownPersonDetected(any());
  }

  @Test
  void runBatch_clusterBelowMinDistinctDays_doesNotCreatePerson() throws Exception {
    List<FaceEmbedding> orphans = saveOrphanEmbeddings(2, sameDays());

    ClusterDto cluster = new ClusterDto();
    cluster.setEmbeddingIds(orphans.stream().map(FaceEmbedding::getId).toList());

    when(mlServiceApiClient.clusterEmbeddings(any())).thenReturn(List.of(cluster));

    mockMvc
        .perform(post("/api/detective-manager/v1/batch/persons"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unknownPersonsCreated").value(0));

    assertEquals(0, personRepository.count());
    verify(notificationPublisher, never()).publishUnknownPersonDetected(any());
  }

  @Test
  void runBatch_noClusters_doesNotCreatePersons() throws Exception {
    saveOrphanEmbeddings(3, differentDays());

    when(mlServiceApiClient.clusterEmbeddings(any())).thenReturn(List.of());

    mockMvc
        .perform(post("/api/detective-manager/v1/batch/persons"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.orphanEmbeddingsProcessed").value(3))
        .andExpect(jsonPath("$.data.unknownPersonsCreated").value(0));

    assertEquals(0, personRepository.count());
  }

  @Test
  void runBatch_deletesExpiredOrphanEmbeddings() throws Exception {
    saveExpiredOrphanEmbedding();
    saveOrphanEmbeddingWithDate(LocalDateTime.now().minusDays(1));

    when(mlServiceApiClient.clusterEmbeddings(any())).thenReturn(List.of());

    mockMvc
        .perform(post("/api/detective-manager/v1/batch/persons"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deletedOrphanEmbeddings").value(1))
        .andExpect(jsonPath("$.data.orphanEmbeddingsProcessed").value(1));
  }

  @Test
  void runBatch_associatesEmbeddingsToCreatedPerson() throws Exception {
    List<FaceEmbedding> orphans = saveOrphanEmbeddings(3, differentDays());

    ClusterDto cluster = new ClusterDto();
    cluster.setEmbeddingIds(orphans.stream().map(FaceEmbedding::getId).toList());

    when(mlServiceApiClient.clusterEmbeddings(any())).thenReturn(List.of(cluster));

    mockMvc.perform(post("/api/detective-manager/v1/batch/persons")).andExpect(status().isOk());

    var allEmbeddings = faceEmbeddingRepository.findAll();
    allEmbeddings.forEach(
        e -> {
          assertNotNull(e.getPerson(), "Embedding should be associated to a person");
          assertEquals(
              FaceEmbeddingStatus.CLUSTERED, e.getStatus(), "Embedding status should be CLUSTERED");
        });
  }

  @Test
  void runBatch_publishesUnknownPersonEventWithDeviceId() throws Exception {
    List<FaceEmbedding> orphans = saveOrphanEmbeddings(3, differentDays());

    ClusterDto cluster = new ClusterDto();
    cluster.setEmbeddingIds(orphans.stream().map(FaceEmbedding::getId).toList());

    when(mlServiceApiClient.clusterEmbeddings(any())).thenReturn(List.of(cluster));

    mockMvc.perform(post("/api/detective-manager/v1/batch/persons")).andExpect(status().isOk());

    verify(notificationPublisher, times(1))
        .publishUnknownPersonDetected(
            argThat(
                event ->
                    DEVICE_UUID.equals(event.getDeviceId())
                        && event.getPersonId() != null
                        && event.getDistinctDays() == 3
                        && event.getImageUrls().equals(List.of(MOCK_IMAGE_URL))));
  }

  private List<FaceEmbedding> saveOrphanEmbeddings(int count, List<LocalDateTime> dates) {
    return dates.stream().limit(count).map(this::saveOrphanEmbeddingWithDate).toList();
  }

  private FaceEmbedding saveOrphanEmbeddingWithDate(LocalDateTime date) {
    FaceEmbedding embedding = new FaceEmbedding();
    embedding.setDeviceId(DEVICE_UUID);
    embedding.setEmbedding(List.of(0.1, 0.2, 0.3));
    embedding.setModelName("ArcFace");
    embedding.setDetectorBackend("retinaface");
    embedding.setCreatedAt(date);
    embedding.setImageUrl(MOCK_IMAGE_URL);
    embedding.setStatus(FaceEmbeddingStatus.WAITING_BATCH);
    return faceEmbeddingRepository.save(embedding);
  }

  private void saveExpiredOrphanEmbedding() {
    saveOrphanEmbeddingWithDate(LocalDateTime.now().minusDays(31));
  }

  private List<LocalDateTime> differentDays() {
    return List.of(
        LocalDateTime.now().minusDays(10),
        LocalDateTime.now().minusDays(5),
        LocalDateTime.now().minusDays(1));
  }

  private List<LocalDateTime> sameDays() {
    LocalDateTime sameDay = LocalDateTime.now().minusDays(2);
    return List.of(sameDay.withHour(9), sameDay.withHour(14));
  }
}
