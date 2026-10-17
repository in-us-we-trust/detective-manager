package com.is2.detective_manager.orchestrator.step.videoanalysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds visit-level observed time from persisted clips, without matching people across clips.
 */
public final class VisitPresenceAccumulator {

  private VisitPresenceAccumulator() {}

  public static double maximumObservedSeconds(List<VideoPresenceChunk> chunks) {
    List<VideoPresenceChunk> ordered =
        chunks.stream()
            .sorted(
                Comparator.comparing(
                    VideoPresenceChunk::receivedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder())))
            .toList();
    Map<String, VideoPresenceChunk> firstReliable = new HashMap<>();
    for (VideoPresenceChunk chunk : ordered) {
      if (chunk.key() != null && reliable(chunk)) {
        firstReliable.putIfAbsent(chunk.key(), chunk);
      }
    }

    Set<String> seen = new HashSet<>();
    List<VideoPresenceChunk> canonical = new ArrayList<>();
    for (VideoPresenceChunk chunk : ordered) {
      VideoPresenceChunk selected = firstReliable.getOrDefault(chunk.key(), chunk);
      Double seconds =
          chunk.key() != null && reliable(selected) ? selected.observedSeconds() : null;
      // Numbered clips have a stable identity, including empty/failed ones. Without a number,
      // repeated absence/failure markers remain barriers: identical empty files may be new clips.
      boolean deduplicate =
          chunk.key() != null && (chunk.number() != null || (seconds != null && seconds > 0));
      if (!deduplicate || seen.add(chunk.key())) {
        canonical.add(
            new VideoPresenceChunk(chunk.key(), chunk.number(), seconds, chunk.receivedAt()));
      }
    }

    double maximum = 0;
    List<VideoPresenceChunk> run = new ArrayList<>();
    for (VideoPresenceChunk chunk : canonical) {
      if (!run.isEmpty() && (run.get(0).number() != null) != (chunk.number() != null)) {
        maximum = Math.max(maximum, maximumWithinRun(run));
        run.clear();
      }
      run.add(chunk);
    }
    return Math.max(maximum, maximumWithinRun(run));
  }

  private static double maximumWithinRun(List<VideoPresenceChunk> run) {
    List<VideoPresenceChunk> ordered =
        !run.isEmpty() && run.get(0).number() != null
            ? run.stream().sorted(Comparator.comparing(VideoPresenceChunk::number)).toList()
            : run;
    double current = 0;
    double maximum = 0;
    Long previousNumber = null;
    for (VideoPresenceChunk chunk : ordered) {
      if (previousNumber != null && chunk.number() != previousNumber + 1) {
        current = 0;
      }
      current =
          reliable(chunk) && chunk.observedSeconds() > 0 ? current + chunk.observedSeconds() : 0;
      maximum = Math.max(maximum, current);
      previousNumber = chunk.number();
    }
    return maximum;
  }

  private static boolean reliable(VideoPresenceChunk chunk) {
    return chunk.observedSeconds() != null
        && Double.isFinite(chunk.observedSeconds())
        && chunk.observedSeconds() >= 0;
  }
}
