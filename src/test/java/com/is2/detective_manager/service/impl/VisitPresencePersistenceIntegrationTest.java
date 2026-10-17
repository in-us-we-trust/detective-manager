package com.is2.detective_manager.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.is2.detective_manager.DbTestUtils;
import com.is2.detective_manager.configuration.VideoAnalysisProperties;
import com.is2.detective_manager.model.entity.AssessmentRecord;
import com.is2.detective_manager.model.entity.ChunkType;
import com.is2.detective_manager.model.entity.Track;
import com.is2.detective_manager.orchestrator.evaluator.RuleBasedDangerEvaluator;
import com.is2.detective_manager.orchestrator.evaluator.Severity;
import com.is2.detective_manager.orchestrator.evaluator.Signal;
import com.is2.detective_manager.orchestrator.step.videoanalysis.VideoPresenceChunk;
import com.is2.detective_manager.repository.AssessmentRecordRepository;
import com.is2.detective_manager.repository.TrackRepository;
import com.is2.detective_manager.service.TrackConsolidation;
import com.is2.detective_manager.service.TrackPersistenceService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class VisitPresencePersistenceIntegrationTest {

  private static final LocalDateTime START = LocalDateTime.of(2026, 10, 16, 12, 0);

  @Autowired private TrackPersistenceService persistence;
  @Autowired private TrackRepository tracks;
  @Autowired private AssessmentRecordRepository assessments;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DbTestUtils db;

  @BeforeEach
  @AfterEach
  void clearDatabase() {
    db.clearTables();
  }

  @Test
  void reconstructionFromCommittedRowsSurvivesServiceRecreationAndKeepsVisitsSeparate() {
    UUID deviceId = UUID.randomUUID();
    Track first = persistence.findOrCreateTrack(UUID.randomUUID(), deviceId);
    Track second = persistence.findOrCreateTrack(UUID.randomUUID(), deviceId);
    for (int number = 1; number <= 3; number++) {
      save(first, number, 5.0);
    }
    save(first, 2, 5.0); // Reenvío persistido en otra transacción.
    save(second, 1, 5.0);
    save(second, 2, 5.0);

    assertEquals(Severity.NORMAL, rebuild(first.getId()).finalAssessment().getSeverity());
    save(first, 4, 5.0);
    TrackConsolidation confirmed = rebuild(first.getId());
    assertEquals(Severity.LLAMATIVO, confirmed.finalAssessment().getSeverity());
    assertFalse(confirmed.shouldNotify());
    assertEquals(Severity.NORMAL, rebuild(second.getId()).finalAssessment().getSeverity());

    AssessmentRecord stored = assessments.findAllByTrackIdOrderByCreatedAtAsc(first.getId()).get(0);
    assertEquals("number:1", stored.getVideoChunkKey());
    assertEquals(1L, stored.getVideoChunkNumber());
    assertEquals(5.0, stored.getVideoPresenceSeconds());
    assertEquals(START.plusSeconds(1), stored.getVideoReceivedAt());
    assertEquals(600_000, stored.getDurationMs()); // Tiempo de ML, no tiempo observado.

    save(first, 5, 0.0);
    assertEquals(Severity.LLAMATIVO, rebuild(first.getId()).finalAssessment().getSeverity());
    assertNotNull(tracks.findById(first.getId()).orElseThrow().getAccumulatedSignals());
  }

  @Test
  void oldAssessmentsWithoutNewColumnsInterruptRatherThanInventObservedTime() {
    Track track = persistence.findOrCreateTrack(UUID.randomUUID(), UUID.randomUUID());
    save(track, 1, 5.0);
    save(track, 2, 5.0);
    AssessmentRecord legacy =
        persistence.saveAssessment(
            track,
            UUID.randomUUID(),
            "VideoAnalysisStep",
            List.of(),
            600_000,
            ChunkType.VIDEO,
            null);
    // Orden de recepción comparable al de los clips modernos.
    legacy.setCreatedAt(START.plusSeconds(3));
    assessments.save(legacy);
    save(track, 3, 5.0);
    save(track, 4, 5.0);
    assertEquals(Severity.NORMAL, rebuild(track.getId()).finalAssessment().getSeverity());
    save(track, 5, 5.0);
    save(track, 6, 5.0);
    assertEquals(Severity.LLAMATIVO, rebuild(track.getId()).finalAssessment().getSeverity());
  }

  private void save(Track track, int number, Double seconds) {
    persistence.saveAssessment(
        track,
        UUID.randomUUID(),
        "VideoAnalysisStep",
        List.of(Signal.NO_SUSPICIOUS_BEHAVIOR),
        600_000,
        ChunkType.VIDEO,
        null,
        new VideoPresenceChunk(
            "number:" + number, (long) number, seconds, START.plusSeconds(number)));
  }

  private TrackConsolidation rebuild(UUID trackId) {
    // Una instancia nueva no conserva memoria. La misma reconstrucción se usa en el retry
    // optimista.
    TrackPersistenceServiceImpl recreated =
        new TrackPersistenceServiceImpl(
            tracks, assessments, new RuleBasedDangerEvaluator(), new VideoAnalysisProperties());
    return new TransactionTemplate(transactionManager)
        .execute(ignored -> recreated.consolidateTrack(trackId, List.of(), false));
  }
}
