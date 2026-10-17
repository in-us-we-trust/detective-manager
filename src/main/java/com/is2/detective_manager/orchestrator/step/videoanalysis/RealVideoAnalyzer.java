package com.is2.detective_manager.orchestrator.step.videoanalysis;

import com.is2.detective_manager.exception.DetectiveException;
import com.is2.detective_manager.exception.ExceptionEnum;
import com.is2.detective_manager.model.dto.VideoAnalysisResponseDto;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Calls the real video analysis endpoint exposed by ml-service. */
@Slf4j
@Component
@Profile("!mock-video")
public class RealVideoAnalyzer implements VideoAnalyzer {

  private final RestTemplate restTemplate;
  private final String baseUrl;

  public RealVideoAnalyzer(
      RestTemplate restTemplate, @Value("${ml.service.base-url}") String baseUrl) {
    this.restTemplate = restTemplate;
    this.baseUrl = baseUrl;
  }

  @Override
  public VideoAnalysisResult analyze(byte[] video) {
    String url = baseUrl + "/api/ml-service/v1/analysis/video";
    log.info("Llamando al ML de análisis de video en: {}", url);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);

    ByteArrayResource videoResource =
        new ByteArrayResource(video) {
          @Override
          public String getFilename() {
            return "video";
          }
        };

    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("video", videoResource);

    VideoAnalysisResponseDto response;
    try {
      response =
          restTemplate.postForObject(
              url, new HttpEntity<>(body, headers), VideoAnalysisResponseDto.class);
    } catch (RestClientException exception) {
      log.error("Falló la llamada al ML de análisis de video", exception);
      throw new DetectiveException(ExceptionEnum.ML_SERVICE_ERROR);
    }

    if (response == null || response.getData() == null) {
      throw invalidResponse();
    }

    VideoAnalysisResponseDto.DataDto data = response.getData();
    VideoAnalysisResult.VideoMetadata metadata = toMetadata(data.getMetadata());
    if (data.getObservations() == null) {
      throw invalidResponse();
    }
    List<VideoAnalysisResult.VideoObservation> observations =
        data.getObservations().stream().filter(this::isUsable).map(this::toObservation).toList();

    log.info("Análisis de video recibido: {} observaciones", observations.size());
    log.info("observations: {}", observations);
    return new VideoAnalysisResult(metadata, observations, data.getObservations().size());
  }

  private VideoAnalysisResult.VideoMetadata toMetadata(
      VideoAnalysisResponseDto.MetadataDto metadata) {
    if (metadata == null
        || metadata.getWidth() == null
        || metadata.getWidth() <= 0
        || metadata.getHeight() == null
        || metadata.getHeight() <= 0
        || !nonnegativeFinite(metadata.getFps())
        || metadata.getFps() == 0
        || !nonnegativeFinite(metadata.getDurationSeconds())
        || metadata.getTotalFrames() == null
        || metadata.getTotalFrames() <= 0) {
      throw invalidResponse();
    }
    return new VideoAnalysisResult.VideoMetadata(
        metadata.getWidth(),
        metadata.getHeight(),
        metadata.getFps(),
        metadata.getDurationSeconds(),
        metadata.getTotalFrames());
  }

  private boolean isUsable(VideoAnalysisResponseDto.ObservationDto observation) {
    return observation != null
        && observation.getFrame() != null
        && observation.getFrame() >= 0
        && nonnegativeFinite(observation.getTimestampSeconds())
        && observation.getTrackId() != null
        && observation.getTrackId() >= 0
        && nonnegativeFinite(observation.getConfidence())
        && observation.getConfidence() <= 1
        && percentage(observation.getBboxX1Pct())
        && percentage(observation.getBboxY1Pct())
        && percentage(observation.getBboxX2Pct())
        && percentage(observation.getBboxY2Pct())
        && observation.getBboxX1Pct() <= observation.getBboxX2Pct()
        && observation.getBboxY1Pct() <= observation.getBboxY2Pct()
        && percentage(observation.getCenterXPct())
        && percentage(observation.getCenterYPct())
        && (observation.getSmoothCenterXPct() == null
            || percentage(observation.getSmoothCenterXPct()))
        && (observation.getSmoothCenterYPct() == null
            || percentage(observation.getSmoothCenterYPct()))
        && percentage(observation.getFootXPct())
        && percentage(observation.getFootYPct())
        && percentage(observation.getAreaFramePct())
        && (observation.getSpeedDiagPctPerSecond() == null
            || nonnegativeFinite(observation.getSpeedDiagPctPerSecond()));
  }

  private boolean percentage(Double value) {
    return nonnegativeFinite(value) && value <= 100;
  }

  private boolean nonnegativeFinite(Double value) {
    return value != null && Double.isFinite(value) && value >= 0;
  }

  private DetectiveException invalidResponse() {
    return new DetectiveException(
        ExceptionEnum.ML_SERVICE_ERROR,
        "The ML service returned an invalid video analysis response.");
  }

  private VideoAnalysisResult.VideoObservation toObservation(
      VideoAnalysisResponseDto.ObservationDto observation) {
    double smoothX =
        observation.getSmoothCenterXPct() != null
            ? observation.getSmoothCenterXPct()
            : observation.getCenterXPct();
    double smoothY =
        observation.getSmoothCenterYPct() != null
            ? observation.getSmoothCenterYPct()
            : observation.getCenterYPct();

    return new VideoAnalysisResult.VideoObservation(
        observation.getFrame(),
        observation.getTimestampSeconds(),
        observation.getTrackId(),
        observation.getConfidence(),
        observation.getBboxX1Pct(),
        observation.getBboxY1Pct(),
        observation.getBboxX2Pct(),
        observation.getBboxY2Pct(),
        observation.getCenterXPct(),
        observation.getCenterYPct(),
        smoothX,
        smoothY,
        observation.getFootXPct(),
        observation.getFootYPct(),
        observation.getAreaFramePct(),
        observation.getSpeedDiagPctPerSecond());
  }
}
