package com.is2.detective_manager.orchestrator.step.videoanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class VideoPresenceChunkTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "chunk_001.mp4",
        "chunk_1.mp4",
        "/tmp/chunk_00001.mp4",
        "C:\\clips\\CHUNK_001.MP4"
      })
  void existingGatewayFilenameIdentifiesLogicalClipDespiteContentChanges(String filename) {
    VideoPresenceChunk clip =
        VideoPresenceChunk.from(filename, new byte[] {1}, 5.0, LocalDateTime.now());
    VideoPresenceChunk retry =
        VideoPresenceChunk.from("chunk_1.mp4", new byte[] {2}, 5.0, LocalDateTime.now());
    assertEquals(1L, clip.number());
    assertEquals(retry.key(), clip.key());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {"", "video.mp4", "chunk_0.mp4", "chunk_-1.mp4", "chunk_9999999999999999.mp4"})
  void olderClientsFallbackToContentForRetryDetection(String filename) {
    VideoPresenceChunk first = VideoPresenceChunk.from(filename, new byte[] {1}, 5.0, null);
    VideoPresenceChunk retry = VideoPresenceChunk.from(filename, new byte[] {1}, null, null);
    VideoPresenceChunk different = VideoPresenceChunk.from(filename, new byte[] {2}, 5.0, null);
    assertNull(first.number());
    assertEquals(first.key(), retry.key());
    assertNotEquals(first.key(), different.key());
  }
}
