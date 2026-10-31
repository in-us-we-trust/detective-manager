#!/usr/bin/env python3
"""Inicializa las dos identidades de validación por HTTP, usando solamente ML real."""

from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID, NAMESPACE_URL, uuid5


MANAGER_API = "/api/detective-manager/v1"
ML_API = "/api/ml-service/v1"
DEFAULT_CONFIG = Path(__file__).resolve().parents[1] / "validation-data/seed.local.json"
MISSING = object()


class InitializationError(Exception):
    """Un estado inválido o un error de servicio impide continuar con el lote."""


@dataclass(frozen=True)
class ReferenceImage:
    path: Path
    payload: bytes
    sha256: str


@dataclass(frozen=True)
class SeedDevice:
    id: str
    label: str
    person_name: str
    images: tuple[ReferenceImage, ...]


@dataclass(frozen=True)
class SeedConfig:
    manager_url: str
    ml_url: str
    timeout: float
    devices: tuple[SeedDevice, ...]


@dataclass
class DeviceState:
    spec: SeedDevice
    device: dict | None
    person: dict | None
    embeddings: list


def normalized_name(value):
    return value.strip().lower() if isinstance(value, str) else ""


def required_text(value, field):
    if not isinstance(value, str) or not value.strip():
        raise InitializationError(f"{field}: debe ser un texto no vacío.")
    return value.strip()


def finite_number(value):
    if type(value) not in (int, float):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def canonical_uuid(value, field):
    try:
        return str(UUID(required_text(value, field)))
    except (ValueError, AttributeError) as error:
        raise InitializationError(f"{field}: debe ser un UUID válido.") from error


def validate_url(value, field):
    value = required_text(value, field).rstrip("/")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError as error:
        raise InitializationError(f"{field}: URL inválida.") from error
    if (
        parsed.scheme not in ("http", "https")
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or any(character.isspace() or ord(character) < 32 for character in value)
        or (port is not None and port == 0)
    ):
        raise InitializationError(f"{field}: usar una URL HTTP(S) sin credenciales, query ni fragmento.")
    return value


def require_keys(value, allowed, field):
    if not isinstance(value, dict):
        raise InitializationError(f"{field}: debe ser un objeto JSON.")
    unknown = set(value) - set(allowed)
    if unknown:
        raise InitializationError(f"{field}: campos desconocidos: {', '.join(sorted(unknown))}.")


def load_config(path):
    """Carga ambas referencias completas antes de permitir cualquier llamada de red."""
    path = Path(path).expanduser().resolve()
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise InitializationError(f"No se pudo leer la configuración local {path}: {error}") from error
    require_keys(
        raw, ("version", "manager_base_url", "ml_base_url", "timeout_seconds", "devices"),
        "configuración",
    )
    if type(raw.get("version")) is not int or raw["version"] != 1:
        raise InitializationError("La configuración debe declarar version: 1.")
    manager_url = validate_url(raw.get("manager_base_url"), "manager_base_url")
    ml_url = validate_url(raw.get("ml_base_url"), "ml_base_url")
    timeout = raw.get("timeout_seconds", 120)
    if not finite_number(timeout) or timeout <= 0:
        raise InitializationError("timeout_seconds debe ser un número positivo finito.")
    entries = raw.get("devices")
    if not isinstance(entries, list) or len(entries) != 2:
        raise InitializationError("devices debe contener exactamente dos dispositivos.")

    devices = []
    ids, labels, names, hashes = set(), set(), set(), set()
    for index, entry in enumerate(entries, 1):
        field = f"devices[{index}]"
        require_keys(entry, ("id", "label", "person_name", "images"), field)
        device_id = canonical_uuid(entry.get("id"), f"{field}.id")
        label = required_text(entry.get("label"), f"{field}.label")
        name = required_text(entry.get("person_name"), f"{field}.person_name")
        if len(label) > 255 or len(name) > 255:
            raise InitializationError(f"{field}: label y person_name no pueden superar 255 caracteres.")
        if device_id in ids or normalized_name(label) in labels or normalized_name(name) in names:
            raise InitializationError("Los UUID, etiquetas y nombres de las dos identidades deben ser distintos.")
        ids.add(device_id)
        labels.add(normalized_name(label))
        names.add(normalized_name(name))
        paths = entry.get("images")
        if not isinstance(paths, list) or not paths:
            raise InitializationError(f"{field}.images: falta al menos una imagen de referencia local.")
        images = []
        for value in paths:
            local_path = Path(required_text(value, f"{field}.images")).expanduser()
            if not local_path.is_absolute():
                local_path = path.parent / local_path
            local_path = local_path.resolve()
            try:
                payload = local_path.read_bytes()
            except OSError as error:
                raise InitializationError(f"Imagen de referencia inaccesible: {local_path}: {error}") from error
            if not payload:
                raise InitializationError(f"Imagen de referencia vacía: {local_path}.")
            digest = hashlib.sha256(payload).hexdigest()
            if digest in hashes:
                raise InitializationError("Hay imágenes de referencia duplicadas, dentro de una identidad o entre ambas.")
            hashes.add(digest)
            images.append(ReferenceImage(local_path, payload, digest))
        devices.append(SeedDevice(device_id, label, name, tuple(images)))
    return SeedConfig(manager_url, ml_url, float(timeout), tuple(devices))


class NoRedirects(HTTPRedirectHandler):
    """No reenviar imágenes a una ubicación distinta de la configurada."""

    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


urlopen = build_opener(NoRedirects()).open


class ApiClient:
    def __init__(self, config):
        self.config = config

    def request(self, base_url, method, path, payload=None, allow_missing=False, binary=False):
        # Los POST de altas nunca se reintentan: un timeout puede ocurrir después del commit.
        body = None if payload is None else json.dumps(payload, allow_nan=False).encode("utf-8")
        headers = {"Accept": "application/octet-stream" if binary else "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = Request(base_url + path, data=body, headers=headers, method=method)
        try:
            with urlopen(request, timeout=self.config.timeout) as response:
                # No aceptar un redirect a otro servidor al enviar imágenes personales.
                if response.geturl() != base_url + path:
                    raise InitializationError(f"{method} {path}: el servicio redirigió la solicitud.")
                result = response.read()
        except HTTPError as error:
            if allow_missing and error.code == 404:
                return MISSING
            raise InitializationError(f"{method} {path}: HTTP {error.code}; no se reintentó la operación.") from error
        except (URLError, OSError, TimeoutError) as error:
            hint = " El servidor pudo haber confirmado el alta; volvé a inspeccionar antes de repetirla." if method == "POST" else ""
            raise InitializationError(f"{method} {path}: servicio inaccesible o timeout.{hint}") from error
        if binary:
            return result
        try:
            return json.loads(result)
        except (ValueError, UnicodeError) as error:
            raise InitializationError(f"{method} {path}: el servicio no devolvió JSON válido.") from error

    def manager(self, method, path, payload=None, allow_missing=False):
        response = self.request(self.config.manager_url, method, path, payload, allow_missing)
        if response is MISSING:
            return None
        if not isinstance(response, dict) or response.get("data") is None:
            raise InitializationError(f"{method} {path}: falta el campo data en la respuesta del Manager.")
        return response["data"]

    def ml(self, path, payload):
        response = self.request(self.config.ml_url, "POST", ML_API + path, payload)
        if not isinstance(response, dict) or not isinstance(response.get("data"), dict):
            raise InitializationError(f"ML {path}: respuesta incompleta.")
        return response["data"]

    def download(self, path):
        if not isinstance(path, str) or not path.startswith("/validation/media/"):
            raise InitializationError("La imagen registrada no pertenece al almacenamiento local de validación.")
        parsed = urlsplit(path)
        if parsed.scheme or parsed.netloc or parsed.query or parsed.fragment or ".." in parsed.path.split("/"):
            raise InitializationError("La URL de imagen registrada es inválida.")
        return self.request(self.config.manager_url, "GET", path, binary=True)

    def check_services(self):
        schema = self.request(self.config.manager_url, "GET", "/v3/api-docs")
        expected = {
            MANAGER_API + "/devices",
            MANAGER_API + "/devices/{deviceId}/persons",
            MANAGER_API + "/devices/{deviceId}/persons/{personId}/face-embeddings",
        }
        paths = schema.get("paths") if isinstance(schema, dict) else None
        if not isinstance(paths, dict) or not expected.issubset(paths):
            raise InitializationError("Detective Manager no expone el contrato esperado para la inicialización.")
        health = self.request(self.config.ml_url, "GET", ML_API + "/health")
        if not isinstance(health, dict) or health.get("status") != "ok":
            raise InitializationError("ML Service no está listo: /health debe devolver status=ok.")


def validate_vector(record, vector_key, model_key, detector_key):
    if not isinstance(record, dict):
        raise InitializationError("Embedding inválido: se esperaba un objeto.")
    vector = record.get(vector_key)
    if (
        not isinstance(vector, list)
        or not vector
        or any(not finite_number(value) for value in vector)
        or not any(value != 0 for value in vector)
    ):
        raise InitializationError("Embedding inválido: el vector debe ser numérico, finito, no vacío y no nulo.")
    required_text(record.get(model_key), "modelo del embedding")
    required_text(record.get(detector_key), "detector del embedding")


def validate_embedding(record, device, person):
    validate_vector(record, "embedding", "modelName", "detectorBackend")
    if type(record.get("id")) is not int or record["id"] <= 0:
        raise InitializationError("Embedding con identificador inválido.")
    if (
        record.get("deviceId") != device.id
        or record.get("personId") != person.get("id")
        or normalized_name(record.get("personName")) != normalized_name(device.person_name)
    ):
        raise InitializationError(f"{device.label}: embedding asociado a otra persona o dispositivo.")
    if type(record.get("dimensions")) is not int or record["dimensions"] != len(record["embedding"]):
        raise InitializationError(f"{device.label}: dimensiones inconsistentes en el embedding.")
    try:
        timestamp = required_text(record.get("createdAt"), "createdAt")
        match = re.fullmatch(
            r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})?",
            timestamp,
        )
        if match is None:
            raise ValueError("Se esperaba una fecha ISO con segundos y precisión de hasta nanosegundos.")
        # Java emite entre 1 y 9 decimales; Python 3.9 sólo acepta 3 o 6.
        # Normalizar únicamente para validar la fecha, sin modificar el registro ni su precisión.
        fraction = match.group(2)
        timestamp = match.group(1)
        if fraction:
            timestamp += "." + fraction[:6].ljust(6, "0")
        timestamp += (match.group(3) or "").replace("Z", "+00:00")
        datetime.fromisoformat(timestamp)
    except ValueError as error:
        raise InitializationError("Embedding con fecha createdAt inválida.") from error


def inspect_device(client, device):
    path = MANAGER_API + "/devices/" + device.id
    existing = client.manager("GET", path, allow_missing=True)
    people = client.manager("GET", path + "/persons")
    if not isinstance(people, list):
        raise InitializationError(f"{device.label}: listado de personas inválido.")
    if existing is not None:
        if (
            not isinstance(existing, dict)
            or existing.get("id") != device.id
            or existing.get("serialNumber") != device.label
            or existing.get("status") not in ("UNCLAIMED", "CLAIMED", "ONLINE", "OFFLINE")
        ):
            raise InitializationError(f"{device.label}: UUID existente con etiqueta o estado inconsistente.")
    elif people:
        raise InitializationError(f"{device.label}: hay personas asociadas a un dispositivo inexistente o inactivo.")
    if not people:
        return DeviceState(device, existing, None, [])
    if len(people) != 1:
        raise InitializationError(f"{device.label}: hay personas duplicadas o inesperadas; no se modifica la base.")
    person = people[0]
    if (
        not isinstance(person, dict)
        or person.get("deviceId") != device.id
        or normalized_name(person.get("name")) != normalized_name(device.person_name)
        or person.get("status") != "KNOWN"
    ):
        raise InitializationError(f"{device.label}: persona incorrecta, cruzada o sin estado KNOWN.")
    canonical_uuid(person.get("id"), "id de persona registrada")
    embeddings = client.manager("GET", path + "/persons/" + person["id"] + "/face-embeddings")
    images = person.get("images")
    if (
        not isinstance(embeddings, list)
        or not isinstance(images, list)
        or len(embeddings) != len(device.images)
        or len(images) != len(device.images)
    ):
        raise InitializationError(f"{device.label}: faltan o sobran imágenes/embeddings de referencia.")
    for embedding in embeddings:
        validate_embedding(embedding, device, person)
    embedding_ids = [embedding["id"] for embedding in embeddings]
    if (
        any(not isinstance(image, dict) or type(image.get("id")) is not int for image in images)
        or len(set(embedding_ids)) != len(embedding_ids)
        or sorted(image["id"] for image in images) != sorted(embedding_ids)
    ):
        raise InitializationError(f"{device.label}: los IDs de imágenes y embeddings no coinciden.")
    stored_hashes = [hashlib.sha256(client.download(image.get("imageUrl"))).hexdigest() for image in images]
    if sorted(stored_hashes) != sorted(image.sha256 for image in device.images):
        raise InitializationError(f"{device.label}: las imágenes guardadas no coinciden con las referencias locales.")
    return DeviceState(device, existing, person, embeddings)


def build_plan(client, config):
    states = [inspect_device(client, device) for device in config.devices]
    ids = [state.person["id"] for state in states if state.person is not None]
    embedding_ids = [entry["id"] for state in states for entry in state.embeddings]
    if len(ids) != len(set(ids)) or len(embedding_ids) != len(set(embedding_ids)):
        raise InitializationError("Las dos identidades comparten IDs de persona o embeddings.")
    return states


def reference_candidates(client, config):
    """Valida todos los rostros reales antes de crear dispositivos o personas."""
    candidates = []
    counter = 0
    for device in config.devices:
        group = []
        temporary_person = str(uuid5(NAMESPACE_URL, "validation-seed/" + device.id))
        for image in device.images:
            counter += 1
            try:
                generated = client.ml(
                    "/analysis/face-embedding",
                    {"image": base64.b64encode(image.payload).decode("ascii")},
                )
                validate_vector(generated, "embedding", "model_name", "detector_backend")
            except InitializationError as error:
                raise InitializationError(f"{device.label}, referencia {image.path}: {error}") from error
            group.append({
                "id": counter,
                "deviceId": device.id,
                "personId": temporary_person,
                "personName": device.person_name,
                "embedding": generated["embedding"],
                "modelName": generated["model_name"],
                "detectorBackend": generated["detector_backend"],
                "dimensions": len(generated["embedding"]),
                "createdAt": datetime.now(timezone.utc).isoformat(),
            })
        candidates.append(group)
    signatures = {
        (entry["modelName"], entry["detectorBackend"], entry["dimensions"])
        for group in candidates for entry in group
    }
    if len(signatures) != 1:
        raise InitializationError("ML generó embeddings con modelos, detectores o dimensiones incompatibles.")
    return candidates


def recognize_reference(client, image, candidates):
    result = client.ml("/analysis/face-recognition", {
        "image": base64.b64encode(image.payload).decode("ascii"), "embeddings": candidates,
    })
    if type(result.get("known")) is not bool:
        raise InitializationError("ML no devolvió un resultado de reconocimiento facial válido.")
    if (
        not finite_number(result.get("threshold"))
        or result["threshold"] < 0
        or not isinstance(result.get("distance_metric"), str)
        or not result["distance_metric"].strip()
        or (result.get("distance") is not None and not finite_number(result["distance"]))
        or (result["known"] and not finite_number(result.get("distance")))
    ):
        raise InitializationError("ML devolvió distancia, umbral o métrica de reconocimiento inválidos.")
    signature = (result.get("model_name"), result.get("detector_backend"))
    if any(signature != (entry["modelName"], entry["detectorBackend"]) for entry in candidates):
        raise InitializationError("El modelo/detector de ML no coincide con los embeddings registrados.")
    if result["known"]:
        match = result.get("closest_match")
        if not isinstance(match, dict) or not any(
            match.get("person_id") == entry["personId"]
            and normalized_name(match.get("person_name")) == normalized_name(entry["personName"])
            and match.get("embedding_id") == entry["id"]
            for entry in candidates
        ):
            raise InitializationError("ML reconoció una identidad que no pertenece a los candidatos enviados.")
    return result["known"]


def verify_recognition(client, config, groups):
    for index, device in enumerate(config.devices):
        own = groups[index]
        for image in device.images:
            if not recognize_reference(client, image, own):
                raise InitializationError(f"{device.label}: ML no reconoce una referencia como la persona esperada.")


def verify_existing_model(states, reference_groups):
    for state, generated in zip(states, reference_groups):
        signature = (generated[0]["modelName"], generated[0]["detectorBackend"], generated[0]["dimensions"])
        if any((entry["modelName"], entry["detectorBackend"], entry["dimensions"]) != signature for entry in state.embeddings):
            raise InitializationError(f"{state.spec.label}: embeddings incompatibles con el ML actual.")


def initialize(client, config, check_only=False):
    client.check_services()
    states = build_plan(client, config)
    references = reference_candidates(client, config)
    verify_recognition(client, config, references)
    verify_existing_model(states, references)
    # Verificar también cualquier inscripción existente antes de escribir el otro dispositivo.
    prior_groups = [state.embeddings or group for state, group in zip(states, references)]
    verify_recognition(client, config, prior_groups)
    if check_only:
        missing = [state.spec.label for state in states if state.device is None or state.person is None]
        if missing:
            raise InitializationError("Inicialización pendiente (modo --check, sin altas): " + ", ".join(missing))
        return states

    apply_plan(client, states)
    final_states = build_plan(client, config)
    if any(state.device is None or state.person is None for state in final_states):
        raise InitializationError("El Manager no conservó todos los registros después de las altas.")
    verify_existing_model(final_states, references)
    verify_recognition(client, config, [state.embeddings for state in final_states])
    return final_states


def apply_plan(client, states):
    """Crea sólo registros ausentes; no agrega imágenes ni actualiza los ya existentes."""
    for state in states:
        device = state.spec
        path = MANAGER_API + "/devices"
        if state.device is None:
            timestamp = datetime.now(timezone.utc).isoformat()
            client.manager("POST", path, {
                "id": device.id, "serialNumber": device.label,
                "lastSeen": timestamp, "lastBoot": timestamp,
            })
        if state.person is None:
            client.manager("POST", path + "/" + device.id + "/persons", {
                "name": device.person_name,
                "images": [base64.b64encode(image.payload).decode("ascii") for image in device.images],
            })


@contextmanager
def initialization_lock(config_path):
    # El bloqueo se libera incluso si el proceso termina; no repara registros ni borra datos.
    import fcntl

    lock_path = config_path.with_name(config_path.name + ".lock")
    try:
        with lock_path.open("a", encoding="utf-8") as lock:
            try:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise InitializationError("Ya hay una inicialización en curso con esta configuración.") from error
            try:
                yield
            finally:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)
    except OSError as error:
        raise InitializationError(f"No se pudo bloquear la configuración local: {error}") from error


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG, help="JSON local con UUIDs y referencias.")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--check-config", action="store_true", help="Validar configuración y archivos, sin red ni altas.")
    modes.add_argument("--check", action="store_true", help="Verificar servicios, registros y ML real, sin crear registros.")
    args = parser.parse_args(argv)
    try:
        config_path = args.config.expanduser().resolve()
        config = load_config(config_path)
        if args.check_config:
            print("Configuración y archivos accesibles. La validez facial se verifica con ML al inicializar. Sin red ni altas.")
            return 0
        with initialization_lock(config_path):
            states = initialize(ApiClient(config), config, check_only=args.check)
        for state in states:
            print(f"OK: {state.spec.label} ({state.spec.id}), una persona KNOWN y {len(state.embeddings)} embedding(s) verificados.")
        print("Inicialización verificada. H2 continúa en memoria; ejecutar nuevamente después de cada reinicio.")
        return 0
    except (InitializationError, OSError) as error:
        print(f"ERROR de inicialización: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
