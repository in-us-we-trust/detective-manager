package com.is2.detective_manager.orchestrator.step.objectrecognition;

import java.util.List;
import java.util.Map;

/**
 * Resultado del análisis de objetos: los objetos detectados (con confidence)
 * y los counts por label (cantidad de cada tipo). Los counts se usan para
 * señales basadas en cantidad (ej. MULTIPLE_PEOPLE).
 */
public record ObjectDetectionResult(List<DetectedObject> objects, Map<String, Integer> counts) {}
