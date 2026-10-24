package com.is2.detective_manager.orchestrator.step.videoanalysis;

import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoMetadata;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoAnalysisResult.VideoObservation;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures.ApproachTrend;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures.BoundaryEvent;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures.BoundaryEventType;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures.BoundarySector;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures.Direction;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures.PersonFeatures;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures.RelativeDistance;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoBehaviorFeatures.TrajectoryPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Calculates stable behavioral features without delegating domain decisions to ml-service. */
@Component
@RequiredArgsConstructor
public class VideoFeatureCalculator {

  private static final double DEFAULT_FRAME_INTERVAL_SECONDS = 1.0 / 30.0;
  private static final double DIAGONAL_NORMALIZER = Math.sqrt(2.0);
  private static final double AREA_JITTER_TOLERANCE_RATIO = 0.05;
  private static final double DIRECTION_PERSISTENCE_ANGLE_DEGREES = 45.0;
  private static final double TRAJECTORY_UPWARD_TOLERANCE_PCT = 3.0;

  private final VideoAnalysisProperties properties;

  public VideoBehaviorFeatures calculate(VideoAnalysisResult analysis) {
    VideoMetadata metadata =
        analysis != null && analysis.metadata() != null
            ? analysis.metadata()
            : new VideoMetadata(0, 0, 0, 0, 0);
    List<VideoObservation> observations =
        sanitize(analysis == null ? null : analysis.observations());
    if (observations.isEmpty()) {
      return new VideoBehaviorFeatures(List.of(), 0, 0, 0, List.of(), 0);
    }

    double frameInterval =
        metadata.fps() > 0 ? 1.0 / metadata.fps() : DEFAULT_FRAME_INTERVAL_SECONDS;
    List<PersonTrack> tracks = stitchFragmentedTracks(observations);
    List<PersonFeatures> people = new ArrayList<>();
    for (int index = 0; index < tracks.size(); index++) {
      people.add(calculatePerson(index + 1, tracks.get(index), metadata, frameInterval));
    }

    Concurrency concurrency = calculateConcurrency(tracks, frameInterval);
    List<RelativeDistance> distances = calculateDistances(tracks, frameInterval);
    return new VideoBehaviorFeatures(
        List.copyOf(people),
        concurrency.totalSeconds(),
        concurrency.longestSeconds(),
        concurrency.maximumPeople(),
        distances,
        observedPresenceSeconds(observations, metadata, frameInterval));
  }

  /** Union of observed frame intervals: simultaneous people never multiply the observed time. */
  private double observedPresenceSeconds(
      List<VideoObservation> observations, VideoMetadata metadata, double frameInterval) {
    if (!finite(metadata.durationSeconds()) || metadata.durationSeconds() <= 0) {
      return 0;
    }
    double total = 0;
    double coveredUntil = 0;
    for (VideoObservation observation : observations) {
      double start = observation.timestampSeconds();
      double end = Math.min(metadata.durationSeconds(), start + frameInterval);
      if (end > coveredUntil && end > start) {
        total += end - Math.max(start, coveredUntil);
        coveredUntil = end;
      }
    }
    return Math.min(total, metadata.durationSeconds());
  }

  private List<VideoObservation> sanitize(List<VideoObservation> raw) {
    if (raw == null) {
      return List.of();
    }

    return raw.stream()
        .filter(java.util.Objects::nonNull)
        .filter(observation -> finite(observation.confidence()))
        .filter(observation -> observation.confidence() >= properties.getMinimumConfidence())
        .filter(observation -> finite(observation.timestampSeconds()))
        .filter(observation -> observation.timestampSeconds() >= 0)
        .filter(observation -> finite(observation.smoothCenterXPct()))
        .filter(observation -> finite(observation.smoothCenterYPct()))
        .filter(observation -> finite(observation.footXPct()))
        .filter(observation -> finite(observation.footYPct()))
        .filter(observation -> finite(observation.areaFramePct()))
        .sorted(
            Comparator.comparingDouble(VideoObservation::timestampSeconds)
                .thenComparingLong(VideoObservation::trackId)
                .thenComparingInt(VideoObservation::frame))
        .toList();
  }

  /** Conservatively joins non-overlapping IDs with spatial and scale continuity. */
  private List<PersonTrack> stitchFragmentedTracks(List<VideoObservation> observations) {
    Map<Long, List<VideoObservation>> byTrackId = new HashMap<>();
    for (VideoObservation observation : observations) {
      byTrackId
          .computeIfAbsent(observation.trackId(), ignored -> new ArrayList<>())
          .add(observation);
    }

    List<PersonTrack> segments =
        byTrackId.entrySet().stream()
            .map(entry -> new PersonTrack(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingDouble(track -> track.first().timestampSeconds()))
            .toList();

    List<PersonTrack> people = new ArrayList<>();
    for (PersonTrack segment : segments) {
      PersonTrack best = null;
      double bestScore = Double.MAX_VALUE;
      for (PersonTrack candidate : people) {
        double gap = segment.first().timestampSeconds() - candidate.last().timestampSeconds();
        if (gap <= 0 || gap > properties.getIdStitchMaximumGapSeconds()) {
          continue;
        }

        double distance = centerDistance(candidate.last(), segment.first());
        double areaDifference =
            relativeDifference(candidate.last().areaFramePct(), segment.first().areaFramePct());
        if (distance > properties.getIdStitchMaximumDistancePct()
            || areaDifference > properties.getIdStitchMaximumAreaChangeRatio()) {
          continue;
        }

        double score =
            distance / Math.max(properties.getIdStitchMaximumDistancePct(), 0.001)
                + areaDifference / Math.max(properties.getIdStitchMaximumAreaChangeRatio(), 0.001)
                + gap / Math.max(properties.getIdStitchMaximumGapSeconds(), 0.001);
        if (score < bestScore) {
          best = candidate;
          bestScore = score;
        }
      }

      if (best == null) {
        people.add(segment.copy());
      } else {
        best.add(segment);
      }
    }

    people.sort(Comparator.comparingDouble(track -> track.first().timestampSeconds()));
    return people;
  }

  private PersonFeatures calculatePerson(
      int personIndex, PersonTrack track, VideoMetadata metadata, double frameInterval) {
    List<VideoObservation> observations = track.observations();
    List<MotionBucket> buckets = motionBuckets(observations);
    double presence = presenceDuration(observations, frameInterval);
    Direction direction = predominantDirection(observations, presence);
    BoundaryEvent entry = entryEvent(observations.get(0));
    BoundaryEvent exit = exitEvent(observations.get(observations.size() - 1), metadata);
    Approach approach = calculateApproach(buckets);
    int directionChanges = directionChanges(buckets);
    StopDurations stops = stopDurations(buckets);
    boolean returned = returnedToPreviousPosition(buckets);

    boolean prolonged = presence >= properties.getProlongedPresenceSeconds();
    boolean longStop = stops.longestSeconds() >= properties.getLongStopSeconds();
    boolean repeatedChanges = directionChanges >= properties.getRepeatedDirectionChanges();
    boolean loitering = prolonged && repeatedChanges;

    List<TrajectoryPoint> trajectory =
        observations.stream()
            .map(
                observation ->
                    new TrajectoryPoint(
                        observation.timestampSeconds(),
                        observation.smoothCenterXPct(),
                        observation.smoothCenterYPct(),
                        observation.footXPct(),
                        observation.footYPct(),
                        observation.areaFramePct(),
                        observation.confidence()))
            .toList();

    return new PersonFeatures(
        personIndex,
        List.copyOf(track.trackIds()),
        presence,
        trajectory,
        direction,
        entry,
        exit,
        approach.trend(),
        approach.areaChangeRatio(),
        approach.logAreaRatePerSecond(),
        directionChanges,
        stops.totalSeconds(),
        stops.longestSeconds(),
        returned,
        prolonged,
        longStop,
        repeatedChanges,
        approach.approaching(),
        approach.fast(),
        loitering);
  }

  private double presenceDuration(List<VideoObservation> observations, double frameInterval) {
    return presenceEpisodes(observations, frameInterval).stream()
        .mapToDouble(interval -> interval.end() - interval.start())
        .sum();
  }

  private List<TimeInterval> presenceEpisodes(
      List<VideoObservation> observations, double frameInterval) {
    if (observations.isEmpty()) {
      return List.of();
    }

    List<TimeInterval> intervals = new ArrayList<>();
    double start = Math.max(0, observations.get(0).timestampSeconds() - frameInterval / 2.0);
    double previous = observations.get(0).timestampSeconds();
    for (int index = 1; index < observations.size(); index++) {
      double current = observations.get(index).timestampSeconds();
      if (current - previous > properties.getTrackingGapToleranceSeconds()) {
        intervals.add(new TimeInterval(start, previous + frameInterval / 2.0));
        start = Math.max(0, current - frameInterval / 2.0);
      }
      previous = current;
    }
    intervals.add(new TimeInterval(start, previous + frameInterval / 2.0));
    return intervals;
  }

  private Direction predominantDirection(
      List<VideoObservation> observations, double presenceSeconds) {
    double xRate = weightedSlope(observations, VideoObservation::smoothCenterXPct);
    double yRate = weightedSlope(observations, VideoObservation::smoothCenterYPct);
    double displacement = Math.hypot(xRate, yRate) * presenceSeconds / DIAGONAL_NORMALIZER;
    if (displacement < properties.getDirectionMinimumDisplacementPct()) {
      return Direction.STATIONARY;
    }

    double angle = Math.toDegrees(Math.atan2(yRate, xRate));
    if (angle >= -22.5 && angle < 22.5) {
      return Direction.RIGHT;
    }
    if (angle >= 22.5 && angle < 67.5) {
      return Direction.DOWN_RIGHT;
    }
    if (angle >= 67.5 && angle < 112.5) {
      return Direction.DOWN;
    }
    if (angle >= 112.5 && angle < 157.5) {
      return Direction.DOWN_LEFT;
    }
    if (angle >= 157.5 || angle < -157.5) {
      return Direction.LEFT;
    }
    if (angle >= -157.5 && angle < -112.5) {
      return Direction.UP_LEFT;
    }
    if (angle >= -112.5 && angle < -67.5) {
      return Direction.UP;
    }
    return Direction.UP_RIGHT;
  }

  private BoundaryEvent entryEvent(VideoObservation observation) {
    BoundarySector sector = boundarySector(observation);
    if (observation.timestampSeconds() <= properties.getVideoBoundaryToleranceSeconds()) {
      return new BoundaryEvent(sector, BoundaryEventType.VIDEO_START);
    }
    if (sector != BoundarySector.INTERIOR) {
      return new BoundaryEvent(sector, BoundaryEventType.FRAME_EDGE);
    }
    return new BoundaryEvent(sector, BoundaryEventType.TRACKING_APPEARANCE);
  }

  private BoundaryEvent exitEvent(VideoObservation observation, VideoMetadata metadata) {
    BoundarySector sector = boundarySector(observation);
    double duration = metadata.durationSeconds();
    if (duration > 0
        && duration - observation.timestampSeconds()
            <= properties.getVideoBoundaryToleranceSeconds()) {
      return new BoundaryEvent(sector, BoundaryEventType.VIDEO_END);
    }
    if (sector != BoundarySector.INTERIOR) {
      return new BoundaryEvent(sector, BoundaryEventType.FRAME_EDGE);
    }
    return new BoundaryEvent(sector, BoundaryEventType.TRACKING_LOSS);
  }

  private BoundarySector boundarySector(VideoObservation observation) {
    double margin = properties.getFrameEdgeMarginPct();
    double left = observation.bboxX1Pct();
    double right = 100 - observation.bboxX2Pct();
    double top = observation.bboxY1Pct();
    double bottom = 100 - observation.bboxY2Pct();
    double nearest = Math.min(Math.min(left, right), Math.min(top, bottom));
    if (nearest > margin) {
      return BoundarySector.INTERIOR;
    }
    if (nearest == left) {
      return BoundarySector.LEFT;
    }
    if (nearest == right) {
      return BoundarySector.RIGHT;
    }
    if (nearest == top) {
      return BoundarySector.TOP;
    }
    return BoundarySector.BOTTOM;
  }

  private Approach calculateApproach(List<MotionBucket> buckets) {
    List<MotionBucket> areaBuckets =
        buckets.stream().filter(bucket -> bucket.areaFramePct() > 0).toList();
    if (areaBuckets.size() < 3) {
      return Approach.stable();
    }

    double duration =
        areaBuckets.get(areaBuckets.size() - 1).timestampSeconds()
            - areaBuckets.get(0).timestampSeconds();
    if (duration < properties.getApproachMinimumDurationSeconds()) {
      return Approach.stable();
    }

    int edgeWindow = Math.max(1, areaBuckets.size() / 3);
    double firstArea = meanArea(areaBuckets.subList(0, edgeWindow));
    double lastArea =
        meanArea(areaBuckets.subList(areaBuckets.size() - edgeWindow, areaBuckets.size()));
    if (firstArea <= 0 || lastArea <= 0) {
      return Approach.stable();
    }

    double areaChange = (lastArea - firstArea) / firstArea;
    double logAreaRate = weightedLogAreaSlope(areaBuckets);
    double increasingRatio = sustainedRatio(areaBuckets, true);
    double decreasingRatio = sustainedRatio(areaBuckets, false);
    double footMovement =
        areaBuckets.get(areaBuckets.size() - 1).footYPct() - areaBuckets.get(0).footYPct();
    boolean trajectorySupportsApproach =
        footMovement >= -TRAJECTORY_UPWARD_TOLERANCE_PCT
            || areaChange >= properties.getFastApproachMinimumAreaIncreaseRatio();

    boolean approaching =
        areaChange >= properties.getApproachMinimumAreaIncreaseRatio()
            && logAreaRate >= properties.getApproachMinimumLogAreaRatePerSecond()
            && increasingRatio >= properties.getApproachMinimumSustainedRatio()
            && trajectorySupportsApproach;
    boolean fast =
        approaching
            && areaChange >= properties.getFastApproachMinimumAreaIncreaseRatio()
            && logAreaRate >= properties.getFastApproachMinimumLogAreaRatePerSecond();
    if (approaching) {
      return new Approach(ApproachTrend.APPROACHING, areaChange, logAreaRate, true, fast);
    }

    boolean movingAway =
        areaChange <= -properties.getApproachMinimumAreaIncreaseRatio()
            && logAreaRate <= -properties.getApproachMinimumLogAreaRatePerSecond()
            && decreasingRatio >= properties.getApproachMinimumSustainedRatio();
    return new Approach(
        movingAway ? ApproachTrend.MOVING_AWAY : ApproachTrend.STABLE,
        areaChange,
        logAreaRate,
        false,
        false);
  }

  private int directionChanges(List<MotionBucket> buckets) {
    List<Velocity> velocities = new ArrayList<>();
    for (int index = 1; index < buckets.size(); index++) {
      MotionBucket previous = buckets.get(index - 1);
      MotionBucket current = buckets.get(index);
      double elapsed = current.timestampSeconds() - previous.timestampSeconds();
      if (elapsed <= 0
          || elapsed
              > properties.getTrackingGapToleranceSeconds() + properties.getMotionWindowSeconds()) {
        continue;
      }
      double dx = current.centerXPct() - previous.centerXPct();
      double dy = current.centerYPct() - previous.centerYPct();
      if (Math.hypot(dx, dy) / DIAGONAL_NORMALIZER
          >= properties.getDirectionMinimumDisplacementPct()) {
        velocities.add(new Velocity(dx, dy));
      }
    }

    if (velocities.size() < 3) {
      return 0;
    }

    int changes = 0;
    Velocity acceptedDirection = velocities.get(0);
    for (int index = 1; index < velocities.size() - 1; index++) {
      Velocity candidate = velocities.get(index);
      Velocity following = velocities.get(index + 1);
      if (angleDegrees(acceptedDirection, candidate) >= properties.getDirectionChangeAngleDegrees()
          && angleDegrees(candidate, following) <= DIRECTION_PERSISTENCE_ANGLE_DEGREES) {
        changes++;
        acceptedDirection = candidate;
        index++;
      }
    }
    return changes;
  }

  private StopDurations stopDurations(List<MotionBucket> buckets) {
    double total = 0;
    double longest = 0;
    double currentStop = 0;
    for (int index = 1; index < buckets.size(); index++) {
      MotionBucket previous = buckets.get(index - 1);
      MotionBucket current = buckets.get(index);
      double elapsed = current.timestampSeconds() - previous.timestampSeconds();
      if (elapsed <= 0
          || elapsed
              > properties.getTrackingGapToleranceSeconds() + properties.getMotionWindowSeconds()) {
        longest = Math.max(longest, currentStop);
        currentStop = 0;
        continue;
      }

      double centerSpeed =
          diagonalDistance(
                  previous.centerXPct(),
                  previous.centerYPct(),
                  current.centerXPct(),
                  current.centerYPct())
              / elapsed;
      double effectiveSpeed = centerSpeed;
      if (current.speedDiagPctPerSecond() != null) {
        effectiveSpeed = Math.max(effectiveSpeed, current.speedDiagPctPerSecond());
      }

      if (effectiveSpeed <= properties.getStationarySpeedPctPerSecond()) {
        total += elapsed;
        currentStop += elapsed;
      } else {
        longest = Math.max(longest, currentStop);
        currentStop = 0;
      }
    }
    return new StopDurations(total, Math.max(longest, currentStop));
  }

  private boolean returnedToPreviousPosition(List<MotionBucket> buckets) {
    for (int originIndex = 0; originIndex < buckets.size() - 2; originIndex++) {
      MotionBucket origin = buckets.get(originIndex);
      for (int farIndex = originIndex + 1; farIndex < buckets.size() - 1; farIndex++) {
        MotionBucket far = buckets.get(farIndex);
        double excursion =
            diagonalDistance(
                origin.centerXPct(), origin.centerYPct(), far.centerXPct(), far.centerYPct());
        if (excursion < properties.getReturnMinimumExcursionPct()) {
          continue;
        }

        Velocity outbound =
            new Velocity(
                far.centerXPct() - origin.centerXPct(), far.centerYPct() - origin.centerYPct());
        for (int returnIndex = farIndex + 1; returnIndex < buckets.size(); returnIndex++) {
          MotionBucket returned = buckets.get(returnIndex);
          if (returned.timestampSeconds() - origin.timestampSeconds()
              < properties.getReturnMinimumDurationSeconds()) {
            continue;
          }
          double returnDistance =
              diagonalDistance(
                  origin.centerXPct(),
                  origin.centerYPct(),
                  returned.centerXPct(),
                  returned.centerYPct());
          Velocity inbound =
              new Velocity(
                  returned.centerXPct() - far.centerXPct(),
                  returned.centerYPct() - far.centerYPct());
          if (returnDistance <= properties.getReturnMaximumDistancePct()
              && angleDegrees(outbound, inbound) >= properties.getDirectionChangeAngleDegrees()) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private Concurrency calculateConcurrency(List<PersonTrack> tracks, double frameInterval) {
    List<TimedDelta> events = new ArrayList<>();
    for (PersonTrack track : tracks) {
      for (TimeInterval interval : presenceEpisodes(track.observations(), frameInterval)) {
        events.add(new TimedDelta(interval.start(), 1));
        events.add(new TimedDelta(interval.end(), -1));
      }
    }
    events.sort(Comparator.comparingDouble(TimedDelta::timestampSeconds));
    if (events.isEmpty()) {
      return new Concurrency(0, 0, 0);
    }

    int active = 0;
    int maximum = 0;
    double total = 0;
    double longest = 0;
    double current = 0;
    double previousTime = events.get(0).timestampSeconds();
    int index = 0;
    while (index < events.size()) {
      double timestamp = events.get(index).timestampSeconds();
      double elapsed = timestamp - previousTime;
      if (active >= 2) {
        total += elapsed;
        current += elapsed;
        longest = Math.max(longest, current);
      } else {
        current = 0;
      }

      int delta = 0;
      while (index < events.size()
          && Double.compare(events.get(index).timestampSeconds(), timestamp) == 0) {
        delta += events.get(index).delta();
        index++;
      }
      active += delta;
      maximum = Math.max(maximum, active);
      if (active < 2) {
        current = 0;
      }
      previousTime = timestamp;
    }
    return new Concurrency(total, longest, maximum);
  }

  private List<RelativeDistance> calculateDistances(
      List<PersonTrack> tracks, double frameInterval) {
    List<RelativeDistance> distances = new ArrayList<>();
    for (int firstIndex = 0; firstIndex < tracks.size(); firstIndex++) {
      for (int secondIndex = firstIndex + 1; secondIndex < tracks.size(); secondIndex++) {
        PersonTrack first = tracks.get(firstIndex);
        PersonTrack second = tracks.get(secondIndex);
        List<Double> samples = synchronizedDistances(first.observations(), second.observations());
        if (samples.isEmpty()) {
          continue;
        }
        double minimum = samples.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double average = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        distances.add(
            new RelativeDistance(
                firstIndex + 1, secondIndex + 1, minimum, average, samples.size() * frameInterval));
      }
    }
    return List.copyOf(distances);
  }

  private List<Double> synchronizedDistances(
      List<VideoObservation> first, List<VideoObservation> second) {
    List<Double> distances = new ArrayList<>();
    int secondIndex = 0;
    for (VideoObservation observation : first) {
      while (secondIndex + 1 < second.size()
          && Math.abs(
                  second.get(secondIndex + 1).timestampSeconds() - observation.timestampSeconds())
              < Math.abs(
                  second.get(secondIndex).timestampSeconds() - observation.timestampSeconds())) {
        secondIndex++;
      }
      VideoObservation other = second.get(secondIndex);
      if (Math.abs(other.timestampSeconds() - observation.timestampSeconds())
          <= properties.getDistanceSynchronizationToleranceSeconds()) {
        distances.add(
            diagonalDistance(
                observation.footXPct(),
                observation.footYPct(),
                other.footXPct(),
                other.footYPct()));
      }
    }
    return distances;
  }

  private List<MotionBucket> motionBuckets(List<VideoObservation> observations) {
    double window = Math.max(properties.getMotionWindowSeconds(), 0.05);
    double firstTimestamp = observations.get(0).timestampSeconds();
    Map<Long, BucketAccumulator> buckets = new java.util.LinkedHashMap<>();
    for (VideoObservation observation : observations) {
      long bucketIndex =
          (long) Math.floor((observation.timestampSeconds() - firstTimestamp) / window);
      buckets.computeIfAbsent(bucketIndex, ignored -> new BucketAccumulator()).add(observation);
    }
    return buckets.values().stream().map(BucketAccumulator::toBucket).toList();
  }

  private double weightedSlope(
      List<VideoObservation> observations,
      java.util.function.ToDoubleFunction<VideoObservation> valueExtractor) {
    double weightSum = observations.stream().mapToDouble(VideoObservation::confidence).sum();
    if (weightSum <= 0) {
      return 0;
    }
    double meanTime =
        observations.stream()
                .mapToDouble(
                    observation -> observation.timestampSeconds() * observation.confidence())
                .sum()
            / weightSum;
    double meanValue =
        observations.stream()
                .mapToDouble(
                    observation ->
                        valueExtractor.applyAsDouble(observation) * observation.confidence())
                .sum()
            / weightSum;
    double numerator = 0;
    double denominator = 0;
    for (VideoObservation observation : observations) {
      double timeOffset = observation.timestampSeconds() - meanTime;
      numerator +=
          observation.confidence()
              * timeOffset
              * (valueExtractor.applyAsDouble(observation) - meanValue);
      denominator += observation.confidence() * timeOffset * timeOffset;
    }
    return denominator == 0 ? 0 : numerator / denominator;
  }

  private double weightedLogAreaSlope(List<MotionBucket> buckets) {
    double weightSum = buckets.stream().mapToDouble(MotionBucket::confidence).sum();
    double meanTime =
        buckets.stream()
                .mapToDouble(bucket -> bucket.timestampSeconds() * bucket.confidence())
                .sum()
            / weightSum;
    double meanLogArea =
        buckets.stream()
                .mapToDouble(bucket -> Math.log(bucket.areaFramePct()) * bucket.confidence())
                .sum()
            / weightSum;
    double numerator = 0;
    double denominator = 0;
    for (MotionBucket bucket : buckets) {
      double timeOffset = bucket.timestampSeconds() - meanTime;
      numerator +=
          bucket.confidence() * timeOffset * (Math.log(bucket.areaFramePct()) - meanLogArea);
      denominator += bucket.confidence() * timeOffset * timeOffset;
    }
    return denominator == 0 ? 0 : numerator / denominator;
  }

  private double sustainedRatio(List<MotionBucket> buckets, boolean increasing) {
    int sustained = 0;
    for (int index = 1; index < buckets.size(); index++) {
      double previous = buckets.get(index - 1).areaFramePct();
      double current = buckets.get(index).areaFramePct();
      boolean matches =
          increasing
              ? current >= previous * (1 - AREA_JITTER_TOLERANCE_RATIO)
              : current <= previous * (1 + AREA_JITTER_TOLERANCE_RATIO);
      if (matches) {
        sustained++;
      }
    }
    return (double) sustained / (buckets.size() - 1);
  }

  private double meanArea(List<MotionBucket> buckets) {
    double weight = buckets.stream().mapToDouble(MotionBucket::confidence).sum();
    return buckets.stream().mapToDouble(bucket -> bucket.areaFramePct() * bucket.confidence()).sum()
        / weight;
  }

  private double centerDistance(VideoObservation first, VideoObservation second) {
    return diagonalDistance(
        first.smoothCenterXPct(),
        first.smoothCenterYPct(),
        second.smoothCenterXPct(),
        second.smoothCenterYPct());
  }

  private double diagonalDistance(double x1, double y1, double x2, double y2) {
    return Math.hypot(x2 - x1, y2 - y1) / DIAGONAL_NORMALIZER;
  }

  private double relativeDifference(double first, double second) {
    double denominator = Math.max(Math.max(Math.abs(first), Math.abs(second)), 0.001);
    return Math.abs(first - second) / denominator;
  }

  private double angleDegrees(Velocity first, Velocity second) {
    double denominator = Math.hypot(first.dx(), first.dy()) * Math.hypot(second.dx(), second.dy());
    if (denominator == 0) {
      return 0;
    }
    double cosine = (first.dx() * second.dx() + first.dy() * second.dy()) / denominator;
    return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine))));
  }

  private boolean finite(double value) {
    return !Double.isNaN(value) && !Double.isInfinite(value);
  }

  private static final class PersonTrack {

    private final Set<Long> trackIds = new LinkedHashSet<>();
    private final List<VideoObservation> observations = new ArrayList<>();

    private PersonTrack(long trackId, List<VideoObservation> observations) {
      this.trackIds.add(trackId);
      this.observations.addAll(observations);
      this.observations.sort(Comparator.comparingDouble(VideoObservation::timestampSeconds));
    }

    private PersonTrack copy() {
      PersonTrack copy = new PersonTrack(trackIds.iterator().next(), observations);
      copy.trackIds.clear();
      copy.trackIds.addAll(trackIds);
      return copy;
    }

    private void add(PersonTrack other) {
      trackIds.addAll(other.trackIds);
      observations.addAll(other.observations);
      observations.sort(Comparator.comparingDouble(VideoObservation::timestampSeconds));
    }

    private VideoObservation first() {
      return observations.get(0);
    }

    private VideoObservation last() {
      return observations.get(observations.size() - 1);
    }

    private Set<Long> trackIds() {
      return trackIds;
    }

    private List<VideoObservation> observations() {
      return observations;
    }
  }

  private static final class BucketAccumulator {

    private double weight;
    private double timestamp;
    private double centerX;
    private double centerY;
    private double footX;
    private double footY;
    private double area;
    private double speed;
    private double speedWeight;

    private void add(VideoObservation observation) {
      double observationWeight = observation.confidence();
      weight += observationWeight;
      timestamp += observation.timestampSeconds() * observationWeight;
      centerX += observation.smoothCenterXPct() * observationWeight;
      centerY += observation.smoothCenterYPct() * observationWeight;
      footX += observation.footXPct() * observationWeight;
      footY += observation.footYPct() * observationWeight;
      area += observation.areaFramePct() * observationWeight;
      if (observation.speedDiagPctPerSecond() != null
          && observation.speedDiagPctPerSecond() >= 0
          && Double.isFinite(observation.speedDiagPctPerSecond())) {
        speed += observation.speedDiagPctPerSecond() * observationWeight;
        speedWeight += observationWeight;
      }
    }

    private MotionBucket toBucket() {
      return new MotionBucket(
          timestamp / weight,
          centerX / weight,
          centerY / weight,
          footX / weight,
          footY / weight,
          area / weight,
          weight,
          speedWeight > 0 ? speed / speedWeight : null);
    }
  }

  private record MotionBucket(
      double timestampSeconds,
      double centerXPct,
      double centerYPct,
      double footXPct,
      double footYPct,
      double areaFramePct,
      double confidence,
      Double speedDiagPctPerSecond) {}

  private record Velocity(double dx, double dy) {}

  private record TimeInterval(double start, double end) {}

  private record TimedDelta(double timestampSeconds, int delta) {}

  private record Concurrency(double totalSeconds, double longestSeconds, int maximumPeople) {}

  private record StopDurations(double totalSeconds, double longestSeconds) {}

  private record Approach(
      ApproachTrend trend,
      double areaChangeRatio,
      double logAreaRatePerSecond,
      boolean approaching,
      boolean fast) {

    private static Approach stable() {
      return new Approach(ApproachTrend.STABLE, 0, 0, false, false);
    }
  }
}
