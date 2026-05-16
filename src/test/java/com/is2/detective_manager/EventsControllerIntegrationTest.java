package com.is2.detective_manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.client.UsersManagerApiClient;
import com.is2.detective_manager.model.dto.NotifyIntruderRequestDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class EventsControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private DbTestUtils dbUtils;

  @MockBean private MlServiceApiClient mlServiceApiClient;
  @MockBean private UsersManagerApiClient usersManagerApiClient;
  @MockBean private CloudinaryStorageClient cloudinaryStorageClient;

  private static final String DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000";
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
  void reportEvent_intruderDetected_returnsCreatedAndNotifiesUsersManager() throws Exception {
    when(mlServiceApiClient.analyzeIntruder(any())).thenReturn(true);

    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", DEVICE_ID)
                .content(
                    """
                    {
                      "image": "%s",
                      "type": "bell"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.eventId").exists())
        .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.isIntruder").value(true))
        .andExpect(jsonPath("$.data.imageUrl").value(MOCK_IMAGE_URL))
        .andExpect(jsonPath("$.data.eventType").value("bell"))
        .andExpect(jsonPath("$.data.timestamp").exists());

    verify(usersManagerApiClient, times(1))
        .notifyIntruder(
            argThat(
                request ->
                    DEVICE_ID.equals(request.getDeviceId())
                        && MOCK_IMAGE_URL.equals(request.getImageUrl())));
  }

  @Test
  void reportEvent_noIntruderDetected_returnsCreatedAndDoesNotNotify() throws Exception {
    when(mlServiceApiClient.analyzeIntruder(any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", DEVICE_ID)
                .content(
                    """
                    {
                      "image": "%s",
                      "type": "motion"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.isIntruder").value(false))
        .andExpect(jsonPath("$.data.eventType").value("motion"));

    verify(usersManagerApiClient, never()).notifyIntruder(any(NotifyIntruderRequestDto.class));
  }

  @Test
  void reportEvent_missingDeviceIdHeader_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .content(
                    """
                    {
                      "image": "%s",
                      "type": "bell"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Required header 'device_id' is missing"));
  }

  @Test
  void reportEvent_invalidDeviceIdFormat_returnsBadRequest() throws Exception {
    when(mlServiceApiClient.analyzeIntruder(any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", "not-a-valid-uuid")
                .content(
                    """
                    {
                      "image": "%s",
                      "type": "bell"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid UUID"));
  }

  @Test
  void reportEvent_missingImage_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", DEVICE_ID)
                .content(
                    """
                    {
                      "type": "bell"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"));
  }

  @Test
  void reportEvent_missingType_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", DEVICE_ID)
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
  void reportEvent_unknownField_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", DEVICE_ID)
                .content(
                    """
                    {
                      "image": "%s",
                      "type": "bell",
                      "unknownField": "value"
                    }
                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getEvent_existingEvent_returnsOk() throws Exception {
    when(mlServiceApiClient.analyzeIntruder(any())).thenReturn(true);

    String response =
        mockMvc
            .perform(
                post("/api/detective-manager/v1/events")
                    .contentType("application/json")
                    .header("device_id", DEVICE_ID)
                    .content(
                        """
                                            {
                                              "image": "%s",
                                              "type": "bell"
                                            }
                                            """
                            .formatted(VALID_BASE64_IMAGE)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String eventId = com.jayway.jsonpath.JsonPath.read(response, "$.data.eventId");

    mockMvc
        .perform(get("/api/detective-manager/v1/events/{eventId}", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(eventId))
        .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data.intruder").value(true))
        .andExpect(jsonPath("$.data.imageUrl").value(MOCK_IMAGE_URL))
        .andExpect(jsonPath("$.data.eventType").value("bell"))
        .andExpect(jsonPath("$.data.timestamp").exists());
  }

  @Test
  void getEvent_nonExistingEvent_returnsNotFound() throws Exception {
    mockMvc
        .perform(
            get(
                "/api/detective-manager/v1/events/{eventId}",
                "123e4567-e89b-12d3-a456-426614174999"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getEvent_invalidEventId_returnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/api/detective-manager/v1/events/{eventId}", "invalid-uuid"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getEvents_withoutFilters_returnsAllEvents() throws Exception {
    when(mlServiceApiClient.analyzeIntruder(any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", DEVICE_ID)
                .content(
                    """
                                    {
                                      "image": "%s",
                                      "type": "motion"
                                    }
                                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/detective-manager/v1/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data[0].eventType").value("motion"));
  }

  @Test
  void getEvents_filterByDeviceIds_returnsFilteredEvents() throws Exception {
    String secondDeviceId = "223e4567-e89b-12d3-a456-426614174000";

    when(mlServiceApiClient.analyzeIntruder(any())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", DEVICE_ID)
                .content(
                    """
                                    {
                                      "image": "%s",
                                      "type": "motion"
                                    }
                                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/detective-manager/v1/events")
                .contentType("application/json")
                .header("device_id", secondDeviceId)
                .content(
                    """
                                    {
                                      "image": "%s",
                                      "type": "bell"
                                    }
                                    """
                        .formatted(VALID_BASE64_IMAGE)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/detective-manager/v1/events").param("deviceIds", DEVICE_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_ID))
        .andExpect(jsonPath("$.data[0].eventType").value("motion"));
  }

  @Test
  void getEvents_invalidDeviceIds_returnsBadRequest() throws Exception {
    mockMvc
        .perform(get("/api/detective-manager/v1/events").param("deviceIds", "invalid-uuid"))
        .andExpect(status().isBadRequest());
  }
}
