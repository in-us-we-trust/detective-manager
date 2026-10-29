package com.is2.detective_manager.client;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!validacion")
@RequiredArgsConstructor
@Slf4j
public class CloudinaryStorageClientImpl implements CloudinaryStorageClient {

  private final Cloudinary cloudinary;

  @Override
  public String uploadImageBytes(byte[] image, UUID deviceId) {
    String publicId = "events/" + deviceId + "/" + UUID.randomUUID();
    log.info("Uploading image bytes to Cloudinary: {}", publicId);
    try {
      Map<?, ?> result =
          cloudinary.uploader().upload(image, ObjectUtils.asMap("public_id", publicId));
      return result.get("secure_url").toString();
    } catch (IOException e) {
      log.error("Failed to upload image bytes to Cloudinary", e);
      throw new RuntimeException("Cloudinary upload failed", e);
    }
  }

  @Override
  public String uploadEventImage(String base64Image, UUID deviceId) {
    String publicId = "events/" + deviceId + "/" + UUID.randomUUID();
    log.info("Uploading event image to Cloudinary: {}", publicId);
    try {
      Map<?, ?> result =
          cloudinary
              .uploader()
              .upload(
                  "data:image/jpeg;base64," + base64Image,
                  ObjectUtils.asMap("public_id", publicId));
      return result.get("secure_url").toString();
    } catch (IOException e) {
      log.error("Failed to upload image to Cloudinary", e);
      throw new RuntimeException("Cloudinary upload failed", e);
    }
  }
}
