package com.is2.detective_manager.orchestrator.step.videoanalysis;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal per-clip evidence. Null observed seconds interrupt the sequence as insufficient data.
 */
public record VideoPresenceChunk(
    String key, Long number, Double observedSeconds, LocalDateTime receivedAt) {

  private static final Pattern NUMBERED_CLIP =
      Pattern.compile("^chunk_([0-9]{1,9})\\.mp4$", Pattern.CASE_INSENSITIVE);

  public static VideoPresenceChunk from(
      String filename, byte[] payload, Double observedSeconds, LocalDateTime receivedAt) {
    String basename = filename == null ? "" : filename.replace('\\', '/');
    basename = basename.substring(basename.lastIndexOf('/') + 1);
    Matcher matcher = NUMBERED_CLIP.matcher(basename);
    if (matcher.matches()) {
      long number = Long.parseLong(matcher.group(1));
      if (number > 0) {
        return new VideoPresenceChunk("number:" + number, number, observedSeconds, receivedAt);
      }
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(payload == null ? new byte[0] : payload);
      return new VideoPresenceChunk(
          "sha256:" + HexFormat.of().formatHex(digest), null, observedSeconds, receivedAt);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required to identify video retries", exception);
    }
  }
}
