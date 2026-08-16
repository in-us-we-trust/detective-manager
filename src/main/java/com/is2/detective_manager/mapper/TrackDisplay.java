package com.is2.detective_manager.mapper;

/** Resultado de presentación de un track: un code estable + un texto legible. */
public record TrackDisplay(TrackAppCode code, String title) {}
