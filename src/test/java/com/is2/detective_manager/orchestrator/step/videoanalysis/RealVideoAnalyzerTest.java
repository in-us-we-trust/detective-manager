package com.is2.detective_manager.orchestrator.step.videoanalysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.VideoAnalysisResponseDto;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

class RealVideoAnalyzerTest {

  @Test
  void responseDto_deserializesMlServiceSnakeCaseContract() throws Exception {
    String json =
        """
        {
          "data": {
            "metadata": {
              "width": 1920,
              "height": 1080,
              "fps": 30.0,
              "duration_s": 10.0,
              "total_frames": 300
            },
            "observations": [{
              "frame": 0,
              "timestamp_s": 0.0,
              "track_id": 1,
              "confidence": 0.94,
              "bbox_x1_pct": 15.2,
              "bbox_y1_pct": 18.5,
              "bbox_x2_pct": 31.8,
              "bbox_y2_pct": 87.4,
              "center_x_pct": 23.5,
              "center_y_pct": 52.95,
              "smooth_center_x_pct": 23.5,
              "smooth_center_y_pct": 52.95,
              "foot_x_pct": 23.5,
              "foot_y_pct": 87.4,
              "area_frame_pct": 11.44,
              "speed_diag_pct_s": 0.0
            }]
          }
        }
        """;

    VideoAnalysisResponseDto response =
        new ObjectMapper().readValue(json, VideoAnalysisResponseDto.class);

    assertEquals(10.0, response.getData().getMetadata().getDurationSeconds());
    assertEquals(300, response.getData().getMetadata().getTotalFrames());
    assertEquals(1L, response.getData().getObservations().get(0).getTrackId());
    assertEquals(11.44, response.getData().getObservations().get(0).getAreaFramePct());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void analyze_sendsVideoMultipartAndMapsResponse() {
    RestTemplate restTemplate = mock(RestTemplate.class);
    VideoAnalysisResponseDto response = response();
    String url = "http://ml-service/api/ml-service/v1/analysis/video";
    when(restTemplate.postForObject(
            eq(url),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(VideoAnalysisResponseDto.class)))
        .thenReturn(response);

    RealVideoAnalyzer analyzer = new RealVideoAnalyzer(restTemplate, "http://ml-service");
    byte[] video = new byte[] {1, 2, 3};
    VideoAnalysisResult result = analyzer.analyze(video);

    ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
    verify(restTemplate)
        .postForObject(eq(url), entityCaptor.capture(), eq(VideoAnalysisResponseDto.class));
    MultiValueMap<String, Object> body =
        (MultiValueMap<String, Object>) entityCaptor.getValue().getBody();
    ByteArrayResource resource = (ByteArrayResource) body.getFirst("video");

    assertEquals("video", resource.getFilename());
    assertArrayEquals(video, resource.getByteArray());
    assertEquals(1920, result.metadata().width());
    assertEquals(1, result.observations().size());
    assertEquals(23.5, result.observations().get(0).smoothCenterXPct());
  }

  @Test
  void analyze_rejectsMissingResponseStructureInsteadOfTreatingItAsAnEmptyVideo() {
    assertMlServiceError(null);
    assertMlServiceError(new VideoAnalysisResponseDto());

    VideoAnalysisResponseDto missingMetadata = response();
    missingMetadata.getData().setMetadata(null);
    assertMlServiceError(missingMetadata);

    VideoAnalysisResponseDto missingObservations = response();
    missingObservations.getData().setObservations(null);
    assertMlServiceError(missingObservations);
  }

  @Test
  void analyze_rejectsIncompleteOrInvalidMetadata() {
    List<Consumer<VideoAnalysisResponseDto.MetadataDto>> invalidMetadata =
        List.of(
            metadata -> metadata.setWidth(null),
            metadata -> metadata.setHeight(0),
            metadata -> metadata.setFps(null),
            metadata -> metadata.setFps(Double.NaN),
            metadata -> metadata.setFps(0.0),
            metadata -> metadata.setDurationSeconds(null),
            metadata -> metadata.setDurationSeconds(-1.0),
            metadata -> metadata.setDurationSeconds(Double.POSITIVE_INFINITY),
            metadata -> metadata.setTotalFrames(null),
            metadata -> metadata.setTotalFrames(0));
    for (Consumer<VideoAnalysisResponseDto.MetadataDto> invalidate : invalidMetadata) {
      VideoAnalysisResponseDto response = response();
      invalidate.accept(response.getData().getMetadata());
      assertMlServiceError(response);
    }
  }

  @Test
  void analyze_acceptsAnExplicitlyEmptyObservationList() {
    VideoAnalysisResponseDto response = response();
    response.getData().setObservations(List.of());

    VideoAnalysisResult result = analyzerReturning(response).analyze(new byte[] {1});

    assertTrue(result.observations().isEmpty());
    assertEquals(0, result.receivedObservationCount());
  }

  @Test
  void analyze_preservesValidObservationsWhenOtherEntriesAreMalformed() {
    VideoAnalysisResponseDto response = response();
    VideoAnalysisResponseDto.ObservationDto valid = response.getData().getObservations().get(0);
    VideoAnalysisResponseDto.ObservationDto missingFields =
        new VideoAnalysisResponseDto.ObservationDto();
    missingFields.setTrackId(2L);
    response.getData().setObservations(Arrays.asList(null, missingFields, valid));

    VideoAnalysisResult result = analyzerReturning(response).analyze(new byte[] {1});

    assertEquals(1, result.observations().size());
    assertEquals(1L, result.observations().get(0).trackId());
    assertEquals(3, result.receivedObservationCount());
  }

  @Test
  void analyze_keepsAllUnusableObservationsDistinctFromNoDetections() {
    VideoAnalysisResponseDto response = response();
    response.getData().getObservations().get(0).setConfidence(null);

    VideoAnalysisResult result = analyzerReturning(response).analyze(new byte[] {1});

    assertTrue(result.observations().isEmpty());
    assertEquals(1, result.receivedObservationCount());
  }

  @Test
  void analyze_rejectsOutOfRangeAndNonFiniteObservationMeasurements() {
    List<Consumer<VideoAnalysisResponseDto.ObservationDto>> invalidObservation =
        List.of(
            observation -> observation.setTrackId(-1L),
            observation -> observation.setConfidence(Double.NaN),
            observation -> observation.setConfidence(1.1),
            observation -> observation.setTimestampSeconds(-1.0),
            observation -> observation.setBboxX1Pct(90.0),
            observation -> observation.setFootYPct(101.0),
            observation -> observation.setAreaFramePct(Double.POSITIVE_INFINITY),
            observation -> observation.setSpeedDiagPctPerSecond(-1.0));
    for (Consumer<VideoAnalysisResponseDto.ObservationDto> invalidate : invalidObservation) {
      VideoAnalysisResponseDto response = response();
      invalidate.accept(response.getData().getObservations().get(0));

      VideoAnalysisResult result = analyzerReturning(response).analyze(new byte[] {1});

      assertTrue(result.observations().isEmpty());
      assertEquals(1, result.receivedObservationCount());
    }
  }

  @Test
  void analyze_preservesLegacyFallbacksForOptionalSmoothingAndSpeed() {
    VideoAnalysisResponseDto response = response();
    VideoAnalysisResponseDto.ObservationDto observation =
        response.getData().getObservations().get(0);
    observation.setSmoothCenterXPct(null);
    observation.setSmoothCenterYPct(null);
    observation.setSpeedDiagPctPerSecond(null);

    VideoAnalysisResult result = analyzerReturning(response).analyze(new byte[] {1});

    assertEquals(1, result.observations().size());
    assertEquals(observation.getCenterXPct(), result.observations().get(0).smoothCenterXPct());
    assertEquals(observation.getCenterYPct(), result.observations().get(0).smoothCenterYPct());
    assertNull(result.observations().get(0).speedDiagPctPerSecond());
  }

  private void assertMlServiceError(VideoAnalysisResponseDto response) {
    DetectiveException exception =
        assertThrows(
            DetectiveException.class, () -> analyzerReturning(response).analyze(new byte[] {1}));
    assertEquals(ExceptionEnum.ML_SERVICE_ERROR.getTitle(), exception.getTitle());
  }

  private RealVideoAnalyzer analyzerReturning(VideoAnalysisResponseDto response) {
    RestTemplate restTemplate = mock(RestTemplate.class);
    when(restTemplate.postForObject(
            eq("http://ml-service/api/ml-service/v1/analysis/video"),
            org.mockito.ArgumentMatchers.any(HttpEntity.class),
            eq(VideoAnalysisResponseDto.class)))
        .thenReturn(response);
    return new RealVideoAnalyzer(restTemplate, "http://ml-service");
  }

  private VideoAnalysisResponseDto response() {
    VideoAnalysisResponseDto.MetadataDto metadata = new VideoAnalysisResponseDto.MetadataDto();
    metadata.setWidth(1920);
    metadata.setHeight(1080);
    metadata.setFps(30.0);
    metadata.setDurationSeconds(10.0);
    metadata.setTotalFrames(300);

    VideoAnalysisResponseDto.ObservationDto observation =
        new VideoAnalysisResponseDto.ObservationDto();
    observation.setFrame(0);
    observation.setTimestampSeconds(0.0);
    observation.setTrackId(1L);
    observation.setConfidence(0.94);
    observation.setBboxX1Pct(15.2);
    observation.setBboxY1Pct(18.5);
    observation.setBboxX2Pct(31.8);
    observation.setBboxY2Pct(87.4);
    observation.setCenterXPct(23.5);
    observation.setCenterYPct(52.95);
    observation.setSmoothCenterXPct(23.5);
    observation.setSmoothCenterYPct(52.95);
    observation.setFootXPct(23.5);
    observation.setFootYPct(87.4);
    observation.setAreaFramePct(11.44);
    observation.setSpeedDiagPctPerSecond(0.0);

    VideoAnalysisResponseDto.DataDto data = new VideoAnalysisResponseDto.DataDto();
    data.setMetadata(metadata);
    data.setObservations(List.of(observation));
    VideoAnalysisResponseDto response = new VideoAnalysisResponseDto();
    response.setData(data);
    return response;
  }
}
