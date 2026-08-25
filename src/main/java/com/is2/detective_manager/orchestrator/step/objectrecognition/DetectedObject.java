package com.is2.detective_manager.orchestrator.step.objectrecognition;

/** Un objeto detectado por el ML: label + confidence (0..1). */
public record DetectedObject(String label, double confidence) {}
