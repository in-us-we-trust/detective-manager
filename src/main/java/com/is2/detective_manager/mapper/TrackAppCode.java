package com.is2.detective_manager.mapper;

/**
 * Códigos de presentación de un track (contrato estable con la app). La app mapea cada code a un
 * ícono/color. Los textos pueden cambiar; estos no.
 */
public enum TrackAppCode {
  WEAPON, // persona armada
  THREAT, // amenaza detectada
  KNOWN_PERSON, // persona reconocida
  UNKNOWN_PERSON, // persona no reconocida
  ACTIVITY // actividad genérica / sin categorizar
}
