"""Recuperación verificable de auditorías y contraste con las fichas aprobadas.

Este módulo no envía evidencias ni invoca modelos. Los criterios semánticos llegan
desde cases.json; la integridad de un procesamiento se informa por separado.
"""

from __future__ import annotations

from copy import deepcopy
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import tempfile
from uuid import UUID
import zlib


RAW_NAME = re.compile(r"ml-\d{3,}\.json\.gz\Z")
QUALITY = {"VALID", "INSUFFICIENT", "FAILED"}
EXPECTED_STEPS = {
    "IMAGE": {"FaceRecognitionStep", "ObjectRecognitionStep"},
    "AUDIO": {"ConversationAnalysisStep"},
    "VIDEO": {"VideoAnalysisStep"},
}


def _hash(data):
    return hashlib.sha256(data).hexdigest()


def _json(data):
    def invalid(value):
        raise ValueError("Número JSON no finito: " + value)
    return json.loads(data, parse_constant=invalid)


def _within(path, root):
    path = path.resolve()
    root = root.resolve()
    if path != root and root not in path.parents:
        raise ValueError("La referencia sale del directorio permitido")
    return path


def _atomic_copy(destination, relative, data):
    destination = destination.resolve()
    target = _within(destination / relative, destination)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        if target.read_bytes() != data:
            raise ValueError("Ya existe un artefacto distinto en el destino: " + relative)
        return
    descriptor, temporary = tempfile.mkstemp(prefix=".validation-", dir=target.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def collect_audit(audit_root, destination, request_id, track_id, device_id,
                  media_type, input_sha256):
    """Copia un principal y sus gzip sin depender de H2 ni inferir resultados.

    destination es la carpeta ``caso-N.N/auditorias``. Los errores se devuelven
    como datos para preservar solicitudes fallidas o de resultado incierto.
    """
    result = {
        "record": None, "path": None, "artifacts": [], "raw_results": [],
        "integrity_errors": [], "analysis_status": None, "audit_status": None,
        "valid_for_quantitative_validation": False,
    }
    errors = result["integrity_errors"]
    try:
        for value in (request_id, track_id, device_id):
            if not isinstance(value, str) or str(UUID(value)) != value:
                raise ValueError("Los identificadores de auditoría deben ser UUID canónicos")
        root = Path(audit_root).resolve()
        track = _within(root / track_id, root)
        principal = _within(track / (request_id + ".json"), track)
        principal_bytes = principal.read_bytes()
        record = _json(principal_bytes)
        if not isinstance(record, dict):
            raise ValueError("El principal de auditoría no es un objeto JSON")
        result["record"] = record
        result["analysis_status"] = record.get("analysisStatus")
        result["audit_status"] = record.get("auditStatus")
        expected = {"requestId": request_id, "trackId": track_id,
                    "deviceId": device_id, "evidenceType": media_type,
                    "sha256": input_sha256}
        for field, value in expected.items():
            if record.get(field) != value:
                errors.append("Identificación inconsistente: " + field)
        if errors:
            return result
        steps = record.get("steps")
        if not isinstance(steps, list):
            errors.append("steps debe ser una lista")
            steps = []
        for step in steps:
            if (not isinstance(step, dict) or step.get("requestId") != request_id
                    or not isinstance(step.get("step"), str)
                    or step.get("quality") not in QUALITY
                    or not isinstance(step.get("signals"), list)
                    or any(not isinstance(signal, str) for signal in step["signals"])):
                errors.append("Step inválido o sin correlación con requestId")
        expected_steps = record.get("expectedSteps")
        if not isinstance(expected_steps, list):
            errors.append("Falta expectedSteps")
        elif record.get("analysisStatus") == "COMPLETE":
            if (any(not isinstance(step, str) for step in expected_steps)
                    or set(expected_steps) != EXPECTED_STEPS.get(media_type, set())
                    or not expected_steps):
                errors.append("Los steps previstos no corresponden al tipo de evidencia")
            actual = [step.get("step") for step in steps if isinstance(step, dict)]
            if sorted(actual) != sorted(expected_steps):
                errors.append("El análisis COMPLETE no contiene todos los steps previstos")
        consolidation = record.get("consolidation")
        if consolidation is not None and (
            not isinstance(consolidation, dict)
            or consolidation.get("trackId") != track_id
            or not isinstance(consolidation.get("accumulatedSignals"), list)
        ):
            errors.append("Consolidación inválida o asociada a otro track")
        if record.get("analysisStatus") == "COMPLETE" and not isinstance(consolidation, dict):
            errors.append("El análisis COMPLETE no tiene consolidación")
        if record.get("analysisStatus") == "COMPLETE" and isinstance(consolidation, dict):
            if (not isinstance(consolidation.get("severity"), str)
                    or not isinstance(consolidation.get("verdict"), str)):
                errors.append("Consolidación COMPLETE sin severidad o veredicto")
        exchanges = record.get("mlResponses")
        if not isinstance(exchanges, list):
            errors.append("mlResponses debe ser una lista")
            exchanges = []
        copied = set()
        for exchange in exchanges:
            try:
                if not isinstance(exchange, dict) or exchange.get("requestId") != request_id:
                    raise ValueError("Intercambio ML sin correlación")
                if record.get("analysisStatus") == "COMPLETE" and (
                    type(exchange.get("httpStatus")) is not int
                    or not 200 <= exchange["httpStatus"] < 300
                    or exchange.get("error") is not None
                ):
                    raise ValueError("Un análisis COMPLETE contiene un intercambio ML fallido")
                relative = exchange.get("path")
                if relative is None:
                    if exchange.get("originalByteLength", 0) > 0:
                        raise ValueError("Falta la referencia al cuerpo ML recibido")
                    continue
                if not isinstance(relative, str) or "\\" in relative:
                    raise ValueError("Referencia gzip inválida")
                if exchange.get("bodyRepresentation") not in (
                    "ORIGINAL_BYTES", "SANITIZED_JSON", "INVALID_JSON_TEXT"
                ):
                    raise ValueError("Representación del cuerpo ML desconocida")
                if (record.get("analysisStatus") == "COMPLETE"
                        and exchange.get("bodyRepresentation") == "INVALID_JSON_TEXT"):
                    raise ValueError("Un análisis COMPLETE contiene una respuesta ML inválida")
                parts = PurePosixPath(relative).parts
                if (len(parts) != 2 or parts[0] != request_id
                        or not RAW_NAME.fullmatch(parts[1])
                        or PurePosixPath(relative).is_absolute()):
                    raise ValueError("Referencia gzip fuera del request o con traversal")
                if relative in copied:
                    raise ValueError("Referencia gzip duplicada")
                raw_path = _within(track / relative, track)
                compressed = raw_path.read_bytes()
                if _hash(compressed) != exchange.get("storedSha256"):
                    raise ValueError("SHA-256 comprimido incorrecto: " + relative)
                if len(compressed) != exchange.get("storedByteLength"):
                    raise ValueError("Tamaño comprimido incorrecto: " + relative)
                body = gzip.decompress(compressed)
                parsed = _json(body)
                if exchange.get("bodyRepresentation") == "ORIGINAL_BYTES":
                    if _hash(body) != exchange.get("originalSha256"):
                        raise ValueError("SHA-256 original incorrecto: " + relative)
                    if len(body) != exchange.get("originalByteLength"):
                        raise ValueError("Tamaño original incorrecto: " + relative)
                _atomic_copy(Path(destination), relative, compressed)
                copied.add(relative)
                result["artifacts"].append({"path": relative, "sha256": _hash(compressed),
                                            "sizeBytes": len(compressed)})
                result["raw_results"].append({"endpoint": exchange.get("endpoint"),
                    "step": exchange.get("step"), "body": parsed, "path": relative})
            except (OSError, ValueError, TypeError, EOFError, zlib.error) as error:
                errors.append(str(error))
        if record.get("analysisStatus") == "COMPLETE" and isinstance(expected_steps, list):
            for step_name in expected_steps:
                if not any(raw.get("step") == step_name for raw in result["raw_results"]):
                    errors.append("Falta respuesta original recuperable para " + str(step_name))
        # El principal se publica al final y preserva incluso el estado FAILED/INSUFFICIENT.
        relative = request_id + ".json"
        _atomic_copy(Path(destination), relative, principal_bytes)
        result["path"] = relative
        result["artifacts"].append({"path": relative, "sha256": _hash(principal_bytes),
                                    "sizeBytes": len(principal_bytes)})
        result["valid_for_quantitative_validation"] = (
            not errors and record.get("auditStatus") == "RECORDED"
            and record.get("analysisStatus") == "COMPLETE"
            and record.get("validForQuantitativeValidation") is True
            and type(record.get("httpStatus")) is int and 200 <= record["httpStatus"] < 300
            and all(step.get("quality") == "VALID" for step in steps)
        )
    except (OSError, ValueError, TypeError, EOFError, zlib.error) as error:
        errors.append(str(error))
    return result


def _record(result):
    audit = result.get("audit") or {}
    return audit.get("record") if isinstance(audit.get("record"), dict) else {}


def _http(result):
    return result.get("httpStatus", result.get("http_status"))


def _trusted(result):
    audit = result.get("audit") or {}
    return not audit.get("integrity_errors") and isinstance(audit.get("record"), dict)


def _valid(result):
    audit = result.get("audit") or {}
    status = _http(result)
    return (type(status) is int and 200 <= status < 300
            and not result.get("error")
            and result.get("httpOutcome", result.get("http_outcome")) not in ("UNCERTAIN", "NOT_SENT")
            and audit.get("valid_for_quantitative_validation") is True
            and _trusted(result))


def _signals(value):
    return {item for item in value if isinstance(item, str)} if isinstance(value, list) else set()


def _consolidation(record):
    value = record.get("consolidation")
    return value if isinstance(value, dict) else {}


def _observations(result, prior_accumulated, prior_known=True):
    record = _record(result)
    steps = record.get("steps") if isinstance(record.get("steps"), list) else []
    signals = set().union(*(_signals(step.get("signals")) for step in steps
                            if isinstance(step, dict))) if steps else set()
    consolidation = _consolidation(record)
    accumulated = _signals(consolidation.get("accumulatedSignals"))
    derived = accumulated - prior_accumulated - signals if prior_known else set()
    labels, transcripts = set(), []
    for raw in (result.get("audit") or {}).get("raw_results", []):
        body = raw.get("body")
        if not isinstance(body, dict):
            continue
        if str(raw.get("endpoint", "")).endswith("/object-detection"):
            data = body.get("data")
            objects = data.get("objects") if isinstance(data, dict) else []
            for item in objects if isinstance(objects, list) else []:
                if isinstance(item, dict) and isinstance(item.get("label"), str):
                    labels.add(item["label"])
        if str(raw.get("endpoint", "")).endswith("/conversation"):
            if isinstance(body.get("transcript"), str):
                transcripts.append(body["transcript"])
    return {"step_signals": sorted(signals), "consolidation_added_signals": sorted(derived),
            "consolidation_signal_attribution": "KNOWN_PREVIOUS_SNAPSHOT" if prior_known else "UNKNOWN_PREVIOUS_SNAPSHOT",
            "signals": sorted(signals | derived), "accumulated_signals": sorted(accumulated),
            "visual_categories": sorted(labels), "transcriptions": transcripts,
            "steps": deepcopy(steps), "valid_processing": _valid(result)}, accumulated


def _comparison(expectation, observed, valid, spec):
    if isinstance(expectation, str):
        expectation = {"signal": expectation, "condition": "VALID_ANALYSIS", "scope": "evidence"}
    signal = expectation.get("signal")
    condition = expectation.get("condition", "VALID_ANALYSIS")
    present = signal in observed
    if condition == "MANUAL_REVIEW":
        status = "REVIEW_REQUIRED"
    elif present and valid:
        status = "PRESENT"
    elif not valid:
        status = "NOT_EVALUABLE"
    elif condition == "SUFFICIENT_MOTION":
        # La suficiencia es una anotación metodológica explícita, no inferida de las reglas.
        sufficient = spec.get("sufficient_motion")
        if isinstance(sufficient, dict):
            sufficient = sufficient.get(signal)
        status = "MISSING" if sufficient is True else "CONDITION_NOT_ESTABLISHED"
    else:
        status = "MISSING"
    return {"signal": signal, "condition": condition, "scope": expectation.get("scope", "evidence"),
            "observed": present, "status": status}


def analyze_case(case, evidence_results):
    """Contrasta expectativas explícitas sin convertir toda señal extra en falso positivo."""
    specs = case.get("evidence", [])
    by_id = {entry.get("id"): entry for entry in evidence_results}
    observations, prior, prior_known = [], set(), True
    for spec in specs:
        result = by_id.get(spec.get("id"), {})
        observation, accumulated = _observations(result, prior, prior_known)
        observation.update({"id": spec.get("id"), "media_type": spec.get("media_type"),
                            "file": spec.get("file"), "http_status": _http(result),
                            "request_id": _record(result).get("requestId")})
        if _trusted(result) and isinstance(_record(result).get("consolidation"), dict):
            prior = accumulated
            prior_known = True
        else:
            prior_known = False
        observations.append(observation)
    modality = {}
    for kind in {spec.get("media_type") for spec in specs}:
        members = [entry for entry in observations if entry["media_type"] == kind]
        modality[kind] = {"signals": sorted({signal for entry in members for signal in entry["signals"]}),
                          "valid_processing": bool(members) and all(entry["valid_processing"] for entry in members)}
    comparisons, forbidden, visuals = [], [], []
    for spec, observation in zip(specs, observations):
        expected_names = set()
        checks = []
        for expectation in spec.get("expected_signals", []):
            scope = expectation.get("scope", "evidence") if isinstance(expectation, dict) else "evidence"
            target = modality[spec.get("media_type")] if scope == "modality" else observation
            check = _comparison(expectation, target["signals"], target["valid_processing"], spec)
            check["evidence_id"] = spec.get("id")
            checks.append(check)
            comparisons.append(check)
            expected_names.add(check["signal"])
        observation["signal_comparison"] = checks
        controlled = list(dict.fromkeys(case.get("unjustified_signals", []) + spec.get("unjustified_signals", [])))
        for signal in controlled:
            check = {"evidence_id": spec.get("id"), "signal": signal,
                     "observed": signal in observation["signals"],
                     "status": ("UNJUSTIFIED_PRESENT" if signal in observation["signals"] else "ABSENT")
                     if observation["valid_processing"] else "NOT_EVALUABLE"}
            forbidden.append(check)
        observation["unjustified_signal_comparison"] = [x for x in forbidden if x["evidence_id"] == spec.get("id")]
        observation["additional_signals"] = sorted(set(observation["signals"]) - expected_names - set(controlled))
        observation["review_notes"] = list(spec.get("review_notes", []))
        for label in spec.get("expected_visual_categories", []):
            visuals.append({"evidence_id": spec.get("id"), "category": label,
                            "observed": label in observation["visual_categories"],
                            "status": ("PRESENT" if label in observation["visual_categories"] else "MISSING")
                            if observation["valid_processing"] else "NOT_EVALUABLE"})
    all_sent = len(evidence_results) == len(specs) and len(by_id) == len(specs)
    processing_complete = bool(specs) and all_sent and all(item["valid_processing"] for item in observations)
    processed = [by_id.get(spec.get("id"), {}) for spec in specs
                 if isinstance(_record(by_id.get(spec.get("id"), {})).get("consolidation"), dict)]
    final_record = _record(processed[-1]) if processed else {}
    final = _consolidation(final_record)
    severity = final.get("severity")
    expected_severity = case.get("expected_final_severity")
    severity_status = ("MATCH" if severity == expected_severity else "MISMATCH") if processing_complete else "NOT_EVALUABLE"
    definite_mismatch = (severity_status == "MISMATCH" or any(x["status"] == "MISSING" for x in comparisons + visuals)
                         or any(x["status"] == "UNJUSTIFIED_PRESENT" for x in forbidden))
    review = any(x["status"] in ("REVIEW_REQUIRED", "CONDITION_NOT_ESTABLISHED") for x in comparisons)
    status = "INCOMPLETE" if not processing_complete else "MISMATCH" if definite_mismatch else "REVIEW_REQUIRED" if review else "MATCH"
    return {"case_id": case.get("id"), "processing_complete": processing_complete,
            "comparison_status": status, "expected_final_severity": expected_severity,
            "observed_final_severity": severity, "final_severity_comparison": severity_status,
            "observed_final_verdict": final.get("verdict"), "observed_final_message": final.get("message"),
            "evidence": observations, "signal_comparisons": comparisons,
            "unjustified_signal_comparisons": forbidden, "visual_category_comparisons": visuals,
            "severity_evolution": [{"evidence_id": spec.get("id"),
                "severity": _consolidation(_record(by_id.get(spec.get("id"), {}))).get("severity"),
                "verdict": _consolidation(_record(by_id.get(spec.get("id"), {}))).get("verdict")}
                for spec in specs],
            "additional_signal_policy": "Las señales adicionales se conservan para revisión contextual; no se clasifican automáticamente como falsos positivos."}
