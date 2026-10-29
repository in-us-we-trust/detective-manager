package com.is2.detective_manager.validation;

import com.is2.detective_manager.client.CloudinaryStorageClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/** Conserva las imágenes del experimento en disco, sin acceder a Cloudinary. */
@Slf4j
public class LocalValidationStorageClient implements CloudinaryStorageClient {

  public static final String MEDIA_URL_PREFIX = "/validation/media/";

  private final Path directory;

  public LocalValidationStorageClient(Path directory) {
    this.directory = directory.toAbsolutePath().normalize();
  }

  @Override
  public String uploadEventImage(String base64Image, UUID deviceId) {
    return uploadImageBytes(Base64.getDecoder().decode(base64Image), deviceId);
  }

  @Override
  public String uploadImageBytes(byte[] image, UUID deviceId) {
    String relativePath = deviceId + "/" + UUID.randomUUID() + ".bin";
    Path destination = directory.resolve(relativePath);
    try {
      Files.createDirectories(destination.getParent());
      Files.write(destination, image, StandardOpenOption.CREATE_NEW);
    } catch (IOException exception) {
      throw new UncheckedIOException("No se pudo guardar la imagen de validación", exception);
    }
    String url = MEDIA_URL_PREFIX + relativePath;
    log.info("[VALIDACION] Imagen guardada localmente: {} -> {}", destination, url);
    return url;
  }
}
