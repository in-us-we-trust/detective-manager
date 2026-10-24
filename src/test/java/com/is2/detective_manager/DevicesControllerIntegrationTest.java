package com.is2.detective_manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.is2.detective_manager.repository.DeviceRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class DevicesControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private DbTestUtils dbUtils;
  @Autowired private DeviceRepository deviceRepository;

  private static final String DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000";
  private static final String LAST_BOOT = "2024-01-01T10:00:00Z";
  private static final String LAST_SEEN = "2024-01-01T11:00:00Z";

  @BeforeEach
  void setUp() {
    dbUtils.clearTables();
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
}
