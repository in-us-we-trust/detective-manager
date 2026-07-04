package com.is2.detective_manager.controller;

import com.is2.detective_manager.exception.ExceptionContent;
import com.is2.detective_manager.model.dto.*;
import com.is2.detective_manager.model.generic.StandardResponse;
import com.is2.detective_manager.service.PersonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/detective-manager/v1/devices/{deviceId}/persons")
public class PersonsController {

  private final PersonService personService;

  @Autowired
  public PersonsController(PersonService personService) {
    this.personService = personService;
  }

  @Operation(
      summary = "Register a known person",
      description =
          "Creates a new known person associated with the specified device. An initial face embedding is generated and stored using the provided image.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Person successfully created"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload or invalid UUID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "502",
            description = "Invalid response or communication error from the ML service",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @PostMapping()
  public ResponseEntity<StandardResponse<PersonResponseDto>> createPerson(
      @PathVariable UUID deviceId, @Valid @RequestBody CreatePersonRequestDto request) {

    var response = personService.createPerson(deviceId, request);

    return ResponseEntity.status(201).body(StandardResponse.of(response));
  }

  @Operation(
      summary = "Get all known persons",
      description =
          "Returns every known person registered for the specified device, ordered by creation date.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Persons successfully retrieved"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid device UUID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @GetMapping()
  public ResponseEntity<StandardResponse<List<PersonResponseDto>>> getPersons(
      @PathVariable UUID deviceId) {

    return ResponseEntity.ok(StandardResponse.of(personService.getPersons(deviceId)));
  }

  @Operation(
      summary = "Get a known person",
      description = "Returns the known person associated with the specified device and person ID.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Person successfully retrieved"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid device UUID or person ID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "404",
            description = "Person not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @GetMapping("/{personId}")
  public ResponseEntity<StandardResponse<PersonResponseDto>> getPerson(
      @PathVariable UUID deviceId, @PathVariable Long personId) {

    return ResponseEntity.ok(StandardResponse.of(personService.getPerson(deviceId, personId)));
  }

  @Operation(
      summary = "Update a known person",
      description =
          "Partially updates a known person. Only the fields present in the request body are updated.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Person successfully updated"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request payload or invalid identifier format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "404",
            description = "Person not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @PatchMapping("/{personId}")
  public ResponseEntity<StandardResponse<PersonResponseDto>> updatePerson(
      @PathVariable UUID deviceId,
      @PathVariable Long personId,
      @Valid @RequestBody UpdatePersonRequestDto request) {

    var response = personService.updatePerson(deviceId, personId, request);

    return ResponseEntity.ok(StandardResponse.of(response));
  }

  @Operation(summary = "Add images to a person")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Images successfully added"),
    @ApiResponse(responseCode = "404", description = "Person not found")
  })
  @PostMapping("/{personId}/images")
  public ResponseEntity<StandardResponse<PersonResponseDto>> addImages(
      @PathVariable Long personId,
      @PathVariable UUID deviceId,
      @Valid @RequestBody AddPersonImagesRequestDto request) {

    var response = personService.addImages(deviceId, personId, request);
    return ResponseEntity.status(201).body(StandardResponse.of(response));
  }

  @Operation(summary = "Delete image from person")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Image deleted"),
    @ApiResponse(responseCode = "404", description = "Person or image not found")
  })
  @DeleteMapping("/{personId}/images/{imageId}")
  public ResponseEntity<Void> deleteImage(
      @PathVariable Long personId, @PathVariable Long imageId, @PathVariable UUID deviceId) {

    personService.deleteImage(deviceId, personId, imageId);
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Delete a known person",
      description =
          "Soft-deletes a person and all associated face embeddings for the specified device.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "Person successfully deleted"),
        @ApiResponse(
            responseCode = "404",
            description = "Person not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @DeleteMapping("/{personId}")
  public ResponseEntity<Void> deletePerson(
      @PathVariable UUID deviceId, @PathVariable Long personId) {

    personService.deletePerson(deviceId, personId);
    return ResponseEntity.noContent().build();
  }

  @Operation(
      summary = "Get face embeddings for a person",
      description = "Returns all face embeddings associated with a specific person and device.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Face embeddings successfully retrieved"),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid device UUID or person ID format",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class))),
        @ApiResponse(
            responseCode = "404",
            description = "Person not found",
            content =
                @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = ExceptionContent.class)))
      })
  @GetMapping("/{personId}/face-embeddings")
  public ResponseEntity<StandardResponse<List<FaceEmbeddingResponseDto>>> getFaceEmbeddings(
      @PathVariable UUID deviceId, @PathVariable Long personId) {

    return ResponseEntity.ok(
        StandardResponse.of(personService.getFaceEmbeddings(deviceId, personId)));
  }
}
