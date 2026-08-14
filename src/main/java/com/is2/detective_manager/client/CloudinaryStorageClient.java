package com.is2.detective_manager.client;

import java.util.UUID;

public interface CloudinaryStorageClient {

  String uploadEventImage(String base64Image, UUID deviceId);

  /** Sube una imagen a partir de bytes crudos y devuelve la URL segura. */
  String uploadImageBytes(byte[] image, UUID deviceId);
}
