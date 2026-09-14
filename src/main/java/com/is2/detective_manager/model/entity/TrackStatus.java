package com.is2.detective_manager.model.entity;

public enum TrackStatus {
  CREATED, // recién llegó el primer chunk, todavía no se analizó nada
  FACE_RECOGNIZED, // step 1 ok: la cara es de las cargadas del timbre
  IMAGE_ANALYZED, // step 2: se analizó armas / cara tapada / expresiones
  AUDIO_TAGGED, // step 3: speech-to-text + etiquetado del texto
  VIDEO_ANALYZED, // se analizaron trayectorias y comportamiento en video
  SENTIMENT_ANALYZED, // step 4: análisis de sentimiento
  SCORED, // se evaluó el criterio/puntaje final
  NOTIFIED, // se disparó "notificar peligro"
  CLOSED // el track terminó (o expiró por timeout)
}
