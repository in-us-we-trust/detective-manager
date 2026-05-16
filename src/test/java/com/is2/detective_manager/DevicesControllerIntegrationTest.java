package com.is2.detective_manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import com.is2.detective_manager.client.MlServiceApiClient;
import com.is2.detective_manager.client.UsersManagerApiClient;
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
public class DevicesControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private DbTestUtils dbUtils;

  @MockBean private MlServiceApiClient mlServiceApiClient;

  @MockBean private UsersManagerApiClient usersManagerApiClient;

  @MockBean private CloudinaryStorageClient cloudinaryStorageClient;

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
}
