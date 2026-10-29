#!/usr/bin/env python3
"""Envía un archivo al orquestador y conserva evidencia de la solicitud HTTP."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import mimetypes
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID, uuid4


DEFAULT_DEVICE_ID = "11111111-1111-4111-8111-111111111111"
EXTENSIONS = {
    "IMAGE": {".jpg", ".jpeg", ".png", ".webp", ".bmp"},
    "AUDIO": {".wav", ".mp3", ".m4a", ".ogg", ".flac"},
    "VIDEO": {".mp4", ".mov", ".avi", ".mkv", ".webm"},
}


def uuid_value(value):
    try:
        return str(UUID(value))
    except ValueError as error:
        raise argparse.ArgumentTypeError("Debe ser un UUID válido.") from error


def positive_timeout(value):
    try:
        parsed = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("El timeout debe ser un número positivo.") from error
    if not 0 < parsed < float("inf"):
        raise argparse.ArgumentTypeError("El timeout debe ser un número positivo finito.")
    return parsed


def infer_media_type(path):
    for media_type, extensions in EXTENSIONS.items():
        if path.suffix.lower() in extensions:
            return media_type
    raise ValueError("Extensión no reconocida; indicá --media-type IMAGE, AUDIO o VIDEO.")


def build_multipart(fields, filename, payload):
    """Conserva los bytes y el nombre original, incluido chunk_NNN.mp4."""
    if "\r" in filename or "\n" in filename:
        raise ValueError("El nombre del archivo no puede contener saltos de línea.")
    boundary = "detective-validation-" + uuid4().hex
    body = bytearray()
    for key, value in fields.items():
        body.extend(("--" + boundary + "\r\n").encode("ascii"))
        body.extend(
            ('Content-Disposition: form-data; name="' + key + '"\r\n\r\n').encode("utf-8")
        )
        body.extend(str(value).encode("utf-8"))
        body.extend(b"\r\n")
    escaped_filename = filename.replace("\\", "\\\\").replace('"', '\\"')
    mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    body.extend(("--" + boundary + "\r\n").encode("ascii"))
    body.extend(
        ('Content-Disposition: form-data; name="file"; filename="' + escaped_filename
         + '"\r\nContent-Type: ' + mime_type + "\r\n\r\n").encode("utf-8")
    )
    body.extend(payload)
    body.extend(("\r\n--" + boundary + "--\r\n").encode("ascii"))
    return boundary, bytes(body)


def decode_response(body):
    decoded = body.decode("utf-8", errors="replace")
    try:
        return json.loads(decoded)
    except ValueError:
        return decoded


def submit_event(path, payload, media_type, device_id, track_id, base_url, timeout):
    fields = {"trackId": track_id, "deviceId": device_id, "mediaType": media_type}
    boundary, body = build_multipart(fields, path.name, payload)
    url = base_url.rstrip("/") + "/api/detective-manager/v1/orchestrator/events"
    request = Request(
        url,
        data=body,
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary},
        method="POST",
    )
    evidence = {
        "caseId": str(uuid4()),
        "input": {
            "path": str(path),
            "sizeBytes": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
        },
        "request": {"url": url, **fields},
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "httpStatus": None,
        "response": None,
        "error": None,
    }
    started = time.monotonic()
    try:
        with urlopen(request, timeout=timeout) as response:
            evidence["httpStatus"] = response.status
            evidence["response"] = decode_response(response.read())
    except HTTPError as error:
        evidence["httpStatus"] = error.code
        evidence["response"] = decode_response(error.read())
        evidence["error"] = str(error)
    except (URLError, TimeoutError, OSError) as error:
        evidence["error"] = str(error)
    finally:
        evidence["finishedAt"] = datetime.now(timezone.utc).isoformat()
        evidence["elapsedMs"] = round((time.monotonic() - started) * 1000, 3)
    return evidence


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Envía imagen, audio o video como multipart y guarda evidencia JSON. "
            "Un HTTP 201 confirma el procesamiento HTTP; revisá la respuesta y los logs "
            "para determinar si los análisis de ML se completaron."
        )
    )
    parser.add_argument("--file", type=Path, required=True, help="Archivo multimedia a enviar.")
    parser.add_argument(
        "--media-type", choices=tuple(EXTENSIONS),
        help="Tipo de archivo; por defecto se infiere de la extensión.",
    )
    parser.add_argument(
        "--device-id", type=uuid_value, default=DEFAULT_DEVICE_ID,
        help="UUID del dispositivo de prueba (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--track-id", type=uuid_value, default=None,
        help="UUID de la visita. Omitilo para crear una visita independiente.",
    )
    parser.add_argument(
        "--base-url", default="http://localhost:8088",
        help="URL base de detective-manager (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--output-dir", type=Path, default=Path("validation-data/results"),
        help="Directorio de evidencias JSON (por defecto: %(default)s).",
    )
    parser.add_argument(
        "--timeout", type=positive_timeout, default=300,
        help="Tiempo máximo de espera HTTP en segundos (por defecto: %(default)s).",
    )
    args = parser.parse_args(argv)
    path = args.file.expanduser().resolve()
    if not path.is_file():
        parser.error("--file debe apuntar a un archivo existente.")
    try:
        payload = path.read_bytes()
        if not payload:
            parser.error("El archivo no puede estar vacío.")
        media_type = args.media_type or infer_media_type(path)
        track_id = args.track_id or str(uuid4())
        evidence = submit_event(
            path, payload, media_type, args.device_id, track_id, args.base_url, args.timeout
        )
    except (OSError, ValueError) as error:
        parser.error(str(error))

    output_dir = args.output_dir.expanduser().resolve()
    try:
        output_dir.mkdir(parents=True, exist_ok=True)
        output_file = output_dir / (evidence["caseId"] + ".json")
        with output_file.open("x", encoding="utf-8") as output:
            json.dump(evidence, output, ensure_ascii=False, indent=2)
            output.write("\n")
    except OSError as error:
        print(json.dumps(evidence, ensure_ascii=False, indent=2))
        print("No se pudo guardar la evidencia: " + str(error), file=sys.stderr)
        return 1

    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    print("Evidencia guardada: " + str(output_file))
    status = evidence["httpStatus"]
    return 0 if status is not None and 200 <= status < 300 and evidence["error"] is None else 1


if __name__ == "__main__":
    sys.exit(main())
