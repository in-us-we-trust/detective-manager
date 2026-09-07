package com.is2.detective_manager.service;

import com.is2.detective_manager.orchestrator.evaluator.Assessment;

/**
 * Resultado de consolidar un track: el assessment final (recalculado sobre todas
 * las señales) y si ESTE llamado es el que debe disparar la notificación de
 * peligro (true solo una vez por track, la primera que llega a DANGER).
 */
public record TrackConsolidation(Assessment finalAssessment, boolean shouldNotify) {}
