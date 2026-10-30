#!/usr/bin/env python3
"""Ejecuta casos aprobados sin alterar evidencias, modelos ni reglas del sistema."""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from uuid import UUID, uuid4


SCRIPT_ROOT = Path(__file__).resolve().parent
REPOSITORY = SCRIPT_ROOT.parent
DEFAULT_CONFIG = REPOSITORY / "validation-data/cases.json"


def load_script(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPT_ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


seed = load_script("validation_batch_seed", "initialize-validation.py")
sender = load_script("validation_batch_sender", "send-validation-event.py")
catalog = load_script("validation_batch_catalog", "validation_cases.py")
results = load_script("validation_batch_results", "validation_results.py")


class ValidationError(Exception):
    pass


def now():
    return datetime.now(timezone.utc).isoformat()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def atomic_bytes(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".batch-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def atomic_json(path, value):
    atomic_bytes(path, (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))


def ensure_validation_branch():
    try:
        branch = subprocess.check_output(
            ["git", "-C", str(REPOSITORY), "branch", "--show-current"],
            text=True, stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValidationError("No se pudo comprobar la rama del repositorio.") from error
    if branch != "validacion":
        raise ValidationError("Este ejecutor sólo está habilitado en la rama validacion.")


def configured_path(parent, value, field):
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(field + ": falta una ruta local.")
    path = Path(value).expanduser()
    return (path if path.is_absolute() else parent / path).resolve()


def inside(path, parent):
    return path == parent or parent in path.parents


def private_path(path):
    """Dentro del repo, los artefactos privados deben quedar bajo validation-data."""
    path = Path(path).resolve()
    if inside(path, REPOSITORY) and not inside(path, REPOSITORY / "validation-data"):
        raise ValidationError("Las configuraciones y resultados privados del repo deben estar en validation-data/.")


def load_config(path):
    path = Path(path).expanduser().resolve()
    private_path(path)
    try:
        encoded = path.read_bytes()
        config = json.loads(encoded)
    except (OSError, ValueError) as error:
        raise ValidationError("No se pudo leer cases.json: " + str(error)) from error
    allowed = {"version", "evidence_root", "seed_config", "audit_directory", "results_directory",
               "timeout_seconds", "audit_wait_seconds", "device_aliases", "expectations_source", "cases"}
    if (not isinstance(config, dict) or set(config) - allowed
            or type(config.get("version")) is not int or config["version"] != 1):
        raise ValidationError("Configuración de lote inválida: version debe ser 1 y no debe haber campos desconocidos.")
    cases = config.get("cases")
    expected_count = len(catalog.APPROVED)
    if not isinstance(cases, list) or len(cases) != expected_count:
        raise ValidationError(f"cases.json debe configurar explícitamente los {expected_count} casos activos aprobados.")
    ids = [case.get("id") for case in cases if isinstance(case, dict)]
    if (len(ids) != expected_count or any(not isinstance(value, str) for value in ids)
            or set(ids) != set(catalog.APPROVED) or len(set(ids)) != expected_count):
        raise ValidationError("Los identificadores de casos faltan, se repiten o no corresponden al catálogo activo.")
    for key, default in (("timeout_seconds", 300), ("audit_wait_seconds", 5)):
        value = config.get(key, default)
        if type(value) not in (int, float) or not seed.finite_number(value) or value <= 0:
            raise ValidationError(key + " debe ser un número positivo finito.")
    paths = {key: configured_path(path.parent, config.get(key, default), key) for key, default in (
        ("evidence_root", "."), ("seed_config", "seed.local.json"),
        ("audit_directory", "audit"), ("results_directory", "resultados"),
    )}
    for key in ("seed_config", "audit_directory", "results_directory"):
        private_path(paths[key])
    if (inside(paths["audit_directory"], paths["results_directory"])
            or inside(paths["results_directory"], paths["audit_directory"])):
        raise ValidationError("El origen de auditorías debe ser independiente del destino de resultados.")
    aliases = config.get("device_aliases", {"juli": "Device Juli", "mate": "Device Mate"})
    if (not isinstance(aliases, dict) or set(aliases) != {"juli", "mate"}
            or any(not isinstance(x, str) or not x.strip() for x in aliases.values())
            or aliases["juli"] == aliases["mate"]):
        raise ValidationError("device_aliases debe asociar juli y mate a etiquetas distintas de seed.local.json.")
    config["device_aliases"] = aliases
    for case_id in catalog.APPROVED:
        category, number = case_id.split(".")
        case_folder = (paths["evidence_root"] / category / number).resolve()
        if (inside(paths["results_directory"], case_folder)
                or inside(case_folder, paths["results_directory"])):
            raise ValidationError("El destino de resultados no puede solapar ninguna carpeta de evidencias.")
    return {"path": path, "encoded": encoded, "config": config, "paths": paths}


def inspect_cases(loaded):
    """Asocia exclusivamente rutas ya explícitas: sólo nombres, extensión y stat."""
    root = loaded["paths"]["evidence_root"]
    seen = {}
    findings = {}
    for case in loaded["config"]["cases"]:
        case_id = case["id"]
        category, number = map(int, case_id.split("."))
        configured_issues = case.get("configuration_issues", [])
        if not isinstance(configured_issues, list) or any(not isinstance(x, str) for x in configured_issues):
            raise ValidationError(f"Caso {case_id}: configuration_issues debe ser una lista de textos.")
        issues = list(configured_issues)
        if case.get("category") != category or case.get("folder") != f"{category}/{number}":
            issues.append("La carpeta debe ser la propia categoría/caso, sin rutas compartidas.")
        declared_folder = root / str(category) / str(number)
        folder = declared_folder.resolve()
        if not inside(folder, root) or declared_folder != folder:
            issues.append("Carpeta fuera del árbol de evidencias o enlace simbólico.")
        person = "juli" if category <= 3 else "mate"
        device = catalog._device(case_id, category)
        if case.get("person") != person or case.get("device") != device or case.get("expected_known") is not (person == device):
            issues.append("Persona/dispositivo/conocimiento no corresponde a la asignación aprobada.")
        if case.get("expected_final_severity") != catalog.APPROVED[case_id][0]:
            issues.append("La severidad no coincide con la ficha aprobada.")
        evidence = case.get("evidence")
        if not isinstance(evidence, list) or not evidence:
            evidence = []
            issues.append("Falta la lista explícita de evidencias.")
        evidence_ids = []
        actual_order = []
        slots_by_kind = {}
        slot_counts = {}
        for slot in catalog.APPROVED[case_id][1]:
            slots_by_kind.setdefault(slot["media_type"], []).append(slot)
        for field in ("unjustified_signals",):
            if not isinstance(case.get(field, []), list) or any(not isinstance(x, str) for x in case.get(field, [])):
                issues.append(field + " debe ser una lista explícita de señales.")
        for entry in evidence:
            if not isinstance(entry, dict):
                issues.append("Evidencia con estructura inválida.")
                continue
            kind = entry.get("media_type")
            evidence_ids.append(entry.get("id"))
            if not isinstance(kind, str):
                issues.append("El tipo de evidencia debe ser un texto.")
                continue
            slots = slots_by_kind.get(kind, [])
            position = slot_counts.get(kind, 0)
            slot_counts[kind] = position + 1
            approved = slots[0] if len(slots) == 1 else slots[position] if position < len(slots) else None
            if approved is None:
                issues.append("Modalidad o cantidad de posiciones no aprobada.")
            else:
                if entry.get("expected_signals") != approved["expected_signals"]:
                    issues.append(f"{entry.get('id')}: señales o condicionamientos diferentes a la ficha aprobada.")
                if entry.get("expected_visual_categories", []) != approved["expected_visual_categories"]:
                    issues.append(f"{entry.get('id')}: categorías visuales diferentes a la ficha aprobada.")
            for field in ("unjustified_signals", "expected_visual_categories", "review_notes"):
                if not isinstance(entry.get(field, []), list) or any(not isinstance(x, str) for x in entry.get(field, [])):
                    issues.append(f"{entry.get('id')}: {field} debe ser una lista de textos.")
            sufficiency = entry.get("sufficient_motion")
            if (sufficiency is not None and type(sufficiency) is not bool
                    and not (isinstance(sufficiency, dict)
                             and all(isinstance(key, str) and type(value) is bool
                                     for key, value in sufficiency.items()))):
                issues.append(f"{entry.get('id')}: sufficient_motion requiere anotaciones booleanas explícitas.")
            if not actual_order or actual_order[-1] != kind:
                actual_order.append(kind)
            filename = entry.get("file")
            if kind not in sender.EXTENSIONS:
                issues.append("Tipo multimedia desconocido.")
            if not isinstance(filename, str) or not filename or Path(filename).name != filename or "\\" in filename or filename in (".", ".."):
                issues.append(f"{entry.get('id')}: archivo pendiente o ruta fuera de la carpeta propia.")
                continue
            original = folder / filename
            resolved = original.resolve()
            if not inside(resolved, folder) or original.is_symlink():
                issues.append(f"{filename}: no se admiten enlaces ni rutas externas al caso.")
                continue
            if resolved in seen:
                issues.append(f"{filename}: ruta reutilizada por {seen[resolved]}.")
                findings.setdefault(seen[resolved].split('/')[0], []).append("Hay una ruta de evidencia compartida.")
            seen[resolved] = case_id + "/" + str(entry.get("id"))
            if kind in sender.EXTENSIONS and resolved.suffix.lower() not in sender.EXTENSIONS[kind]:
                issues.append(f"{filename}: extensión incompatible con {kind}.")
            try:
                if not resolved.is_file() or resolved.stat().st_size == 0:
                    issues.append(f"{filename}: archivo faltante o vacío.")
            except OSError as error:
                issues.append(f"{filename}: {error}")
            if (inside(loaded["paths"]["results_directory"], folder)
                    or inside(folder, loaded["paths"]["results_directory"])):
                issues.append("El destino de resultados no puede solapar una carpeta de evidencias.")
            expectations = entry.get("expected_signals", [])
            if not isinstance(expectations, list) or any(
                not isinstance(item, dict) or not isinstance(item.get("signal"), str)
                or item.get("condition") not in ("VALID_ANALYSIS", "SUFFICIENT_MOTION", "MANUAL_REVIEW")
                or item.get("scope") not in ("evidence", "modality") for item in expectations
            ):
                issues.append(f"{filename}: expectativas de señales inválidas.")
        expected_order = []
        for slot in catalog.APPROVED[case_id][1]:
            if not expected_order or expected_order[-1] != slot["media_type"]:
                expected_order.append(slot["media_type"])
        if actual_order != expected_order:
            issues.append("El orden de modalidades no coincide con la ficha aprobada.")
        if case_id == "6.4" and [x.get("media_type") for x in evidence if isinstance(x, dict)].count("IMAGE") != 2:
            issues.append("6.4 requiere exactamente dos imágenes explícitas y distintas.")
        if (any(not isinstance(x, str) or not x for x in evidence_ids)
                or len([x for x in evidence_ids if isinstance(x, str)]) != len(set(x for x in evidence_ids if isinstance(x, str)))):
            issues.append("Los IDs de evidencia faltan o se repiten.")
        if case.get("configuration_status") != "READY":
            issues.append("Caso marcado PENDING; resolver su configuración explícita antes de enviarlo.")
        findings[case_id] = list(dict.fromkeys(findings.get(case_id, []) + issues))
    return findings


def select_cases(config, case_id=None, category=None):
    return [case for case in config["cases"] if
            (case_id is None or case["id"] == case_id)
            and (category is None or case["category"] == category)]


def device_mapping(loaded, seed_config):
    mapped = {}
    for alias, label in loaded["config"]["device_aliases"].items():
        matches = [device for device in seed_config.devices if device.label == label]
        if len(matches) != 1:
            raise ValidationError(f"El alias {alias} no coincide con un dispositivo único en seed.local.json.")
        if seed.normalized_name(matches[0].person_name) != alias:
            raise ValidationError(f"El alias {alias} está asociado a la persona equivocada en seed.local.json.")
        mapped[alias] = matches[0]
    if mapped["juli"].id == mapped["mate"].id:
        raise ValidationError("Los dos alias no pueden usar el mismo dispositivo.")
    return mapped


def read_only_preflight(loaded, selected, findings):
    report = {"mode": "CHECK", "checkedAt": now(), "servicesAvailable": False,
              "initializationPending": None, "errors": [], "cases": []}
    for case in selected:
        report["cases"].append({"id": case["id"], "status": "PENDING" if findings[case["id"]] else "READY",
                                "issues": findings[case["id"]]})
    try:
        seed_config = seed.load_config(loaded["paths"]["seed_config"])
        device_mapping(loaded, seed_config)
        client = seed.ApiClient(seed_config)
        client.check_services()
        report["servicesAvailable"] = True
        states = seed.build_plan(client, seed_config)
        report["initializationPending"] = any(state.device is None or state.person is None for state in states)
        if report["initializationPending"]:
            report["errors"].append("H2 requiere inicialización; --check no crea registros ni ejecuta inferencias.")
    except (seed.InitializationError, ValidationError, OSError) as error:
        report["errors"].append(str(error))
    report["ready"] = not report["errors"] and all(not findings[case["id"]] for case in selected)
    return report


def recover_request_id(audit_root, track_id, evidence, sha256):
    """Recupera sólo una coincidencia local inequívoca; nunca reenvía una solicitud."""
    matches = []
    for path in (audit_root / track_id).glob("*.json"):
        try:
            record = json.loads(path.read_bytes())
            if (record.get("sha256") == sha256 and record.get("filename") == evidence["file"]
                    and record.get("evidenceType") == evidence["media_type"]
                    and record.get("trackId") == track_id and str(UUID(record["requestId"])) == path.stem):
                matches.append(record["requestId"])
        except (OSError, ValueError, KeyError, TypeError):
            continue
    return matches[0] if len(matches) == 1 else None


def retrieve_audit(loaded, case_folder, evidence, response, track_id, device_id, input_sha):
    audit_header = response.get("validationAudit") or {}
    request_id = audit_header.get("requestId")
    origin = "HEADER"
    if not isinstance(request_id, str):
        request_id = recover_request_id(loaded["paths"]["audit_directory"], track_id, evidence, input_sha)
        origin = "LOCAL_UNIQUE_MATCH"
    if not request_id:
        return {"record": None, "path": None, "artifacts": [], "raw_results": [],
                "integrity_errors": ["No se recibió requestId ni se encontró una auditoría local inequívoca."],
                "valid_for_quantitative_validation": False, "recovery": origin}
    try:
        if str(UUID(request_id)) != request_id:
            raise ValueError("requestId no canónico")
    except (ValueError, AttributeError):
        return {"record": None, "path": None, "artifacts": [], "raw_results": [],
                "integrity_errors": ["Header requestId inválido."], "valid_for_quantitative_validation": False}
    deadline = time.monotonic() + loaded["config"].get("audit_wait_seconds", 5)
    principal = loaded["paths"]["audit_directory"] / track_id / (request_id + ".json")
    while not principal.is_file() and time.monotonic() < deadline:
        time.sleep(min(0.1, max(0, deadline - time.monotonic())))
    audit = results.collect_audit(loaded["paths"]["audit_directory"], case_folder / "auditorias",
                                  request_id, track_id, device_id, evidence["media_type"], input_sha)
    audit["recovery"] = origin
    record = audit.get("record") or {}
    if record and record.get("filename") != evidence["file"]:
        audit["integrity_errors"].append("El nombre de archivo de la auditoría no coincide con la evidencia enviada.")
    if record and response.get("httpStatus") is not None and record.get("httpStatus") != response["httpStatus"]:
        audit["integrity_errors"].append("El estado HTTP recibido difiere del registrado en la auditoría.")
    functional = response.get("response")
    data = functional.get("data") if isinstance(functional, dict) else None
    if (response.get("httpStatus") is not None and 200 <= response["httpStatus"] < 300
            and not response.get("error") and not isinstance(data, dict)):
        audit["integrity_errors"].append("La respuesta HTTP exitosa no conserva el contrato data esperado.")
    if isinstance(data, dict) and isinstance(record.get("response"), dict) and record["response"] != data:
        audit["integrity_errors"].append("La respuesta funcional difiere de la registrada en la auditoría.")
    if (audit_header.get("status") != "RECORDED"
            or audit_header.get("validForQuantitativeValidation") is not True
            or origin != "HEADER"):
        audit["valid_for_quantitative_validation"] = False
        audit["integrity_errors"].append("Headers ausentes, inválidos o ejecución no válida; el resultado HTTP no se presume exitoso.")
    if audit["integrity_errors"]:
        audit["valid_for_quantitative_validation"] = False
    return audit


def serializable_evidence(entry):
    output = deepcopy(entry)
    audit = output.get("audit") or {}
    audit.pop("record", None)
    for raw in audit.get("raw_results", []):
        raw.pop("body", None)
    return output


def execute_case(loaded, case, mapped_device, execution_id, execution_folder):
    folder = execution_folder / ("caso-" + case["id"])
    folder.mkdir(parents=True, exist_ok=False)
    track_id = str(uuid4())
    document = {"executionId": execution_id, "caseId": case["id"], "trackId": track_id,
                "deviceId": mapped_device.id, "configuration": deepcopy(case), "startedAt": now(),
                "status": "RUNNING", "evidence": [], "errors": []}
    atomic_json(folder / "resultado.json", document)
    collected = []
    uncertain = False
    for index, evidence in enumerate(case["evidence"], 1):
        source = loaded["paths"]["evidence_root"] / case["folder"] / evidence["file"]
        entry = {"id": evidence["id"], "order": index, "media_type": evidence["media_type"],
                 "file": evidence["file"], "sourcePath": str(source), "startedAt": now()}
        if uncertain:
            entry.update({"completedAt": now(), "httpStatus": None, "httpOutcome": "NOT_SENT",
                          "error": "No enviada: la solicitud previa tiene resultado HTTP incierto; repetir el caso sólo con un track nuevo."})
            collected.append(entry)
            document["evidence"] = [serializable_evidence(item) for item in collected]
            atomic_json(folder / "resultado.json", document)
            continue
        try:
            if source.is_symlink() or source.resolve() != source or not inside(source.resolve(), (loaded["paths"]["evidence_root"] / case["folder"]).resolve()):
                raise OSError("La ruta cambió o sale de la carpeta de la evidencia.")
            stat_before = source.stat()
            payload = source.read_bytes()
            stat_after = source.stat()
            if (stat_before.st_size, stat_before.st_mtime_ns) != (stat_after.st_size, stat_after.st_mtime_ns):
                raise OSError("El archivo cambió durante su lectura; no se envió.")
            if not payload:
                raise OSError("La evidencia quedó vacía antes de enviarse.")
            entry["input_sha256"] = digest(payload)
            entry["sizeBytes"] = len(payload)
            entry["httpOutcome"] = "SENDING"
            document["evidence"] = [serializable_evidence(item) for item in collected + [entry]]
            atomic_json(folder / "resultado.json", document)
            # Un único envío por evidencia. Ni DANGER ni un error provocan reintentos.
            network_started = time.monotonic()
            try:
                response = sender.submit_event(source, payload, evidence["media_type"], mapped_device.id,
                                               track_id, loaded["seed"].manager_url,
                                               loaded["config"].get("timeout_seconds", 300))
            except Exception as error:
                # También cubre lecturas HTTP truncadas que urllib puede propagar después del envío.
                response = {"httpStatus": None, "response": None, "validationAudit": None,
                            "error": type(error).__name__ + ": " + str(error),
                            "startedAt": entry["startedAt"], "finishedAt": now(),
                            "elapsedMs": round((time.monotonic() - network_started) * 1000, 3)}
            entry.update(response)
            entry["clientEvidenceId"] = entry.pop("caseId", None)
            entry["caseId"] = case["id"]
            entry["httpOutcome"] = ("UNCERTAIN" if response.get("httpStatus") is None
                                    or (response.get("error") and 200 <= response["httpStatus"] < 300) else
                                    "ACCEPTED" if 200 <= response["httpStatus"] < 300 else "REJECTED")
            uncertain = entry["httpOutcome"] == "UNCERTAIN"
            entry["audit"] = retrieve_audit(loaded, folder, evidence, response, track_id,
                                             mapped_device.id, entry["input_sha256"])
            if uncertain:
                entry["audit"]["valid_for_quantitative_validation"] = False
                entry["audit"].setdefault("integrity_errors", []).append(
                    "La entrega o la lectura de la respuesta HTTP es incierta; no se presume éxito.")
        except (OSError, ValueError, TypeError, ValidationError) as error:
            entry.update({"error": str(error), "httpStatus": entry.get("httpStatus"),
                          "httpOutcome": entry.get("httpOutcome", "NOT_SENT")})
            document["errors"].append({"evidenceId": evidence["id"], "message": str(error)})
        entry["completedAt"] = now()
        collected.append(entry)
        document["evidence"] = [serializable_evidence(item) for item in collected]
        atomic_json(folder / "resultado.json", document)
    analysis = results.analyze_case(case, collected)
    document.update({"finishedAt": now(), "analysis": analysis,
                     "status": "COMPLETED" if analysis["processing_complete"] else "INCOMPLETE"})
    atomic_json(folder / "resultado.json", document)
    return document


def run_batch(loaded, selected, findings):
    execution_id = str(uuid4())
    execution_folder = loaded["paths"]["results_directory"] / execution_id
    execution_folder.mkdir(parents=True, exist_ok=False)
    manifest = {"executionId": execution_id, "startedAt": now(), "branch": "validacion",
                "status": "PREPARING", "configurationSha256": digest(loaded["encoded"]),
                "configurationSnapshot": "cases.snapshot.json", "seedSnapshot": "seed.snapshot.json",
                "selectedCases": [case["id"] for case in selected], "cases": [], "errors": []}
    atomic_bytes(execution_folder / "cases.snapshot.json", loaded["encoded"])
    atomic_json(execution_folder / "manifest.json", manifest)
    ready = [case for case in selected if not findings[case["id"]]]
    stage = "INITIALIZATION"
    try:
        seed_bytes = loaded["paths"]["seed_config"].read_bytes()
        atomic_bytes(execution_folder / "seed.snapshot.json", seed_bytes)
        loaded["seed"] = seed.load_config(loaded["paths"]["seed_config"])
        if loaded["paths"]["seed_config"].read_bytes() != seed_bytes:
            raise ValidationError("seed.local.json cambió durante la preparación; no se inicializó el lote.")
        mapping = device_mapping(loaded, loaded["seed"])
        manifest["devices"] = {alias: {"id": device.id, "label": device.label}
                               for alias, device in mapping.items()}
        manifest["referenceImages"] = {
            alias: [{"path": str(image.path), "sha256": image.sha256, "sizeBytes": len(image.payload)}
                    for image in device.images] for alias, device in mapping.items()}
        if ready:
            # Reutilizar íntegro el inicializador: no duplicar inscripción ni lógica de ML.
            with seed.initialization_lock(loaded["paths"]["seed_config"]):
                seed.initialize(seed.ApiClient(loaded["seed"]), loaded["seed"])
            manifest["initialization"] = "VERIFIED"
        stage = "CASES"
        for case in selected:
            if findings[case["id"]]:
                case_folder = execution_folder / ("caso-" + case["id"])
                document = {"executionId": execution_id, "caseId": case["id"],
                            "trackId": str(uuid4()), "deviceId": mapping[case["device"]].id,
                            "configuration": deepcopy(case), "status": "PENDING",
                            "errors": findings[case["id"]], "evidence": []}
                atomic_json(case_folder / "resultado.json", document)
            else:
                try:
                    document = execute_case(loaded, case, mapping[case["device"]], execution_id, execution_folder)
                except (OSError, ValueError, ValidationError) as error:
                    result_path = execution_folder / ("caso-" + case["id"]) / "resultado.json"
                    try:
                        document = json.loads(result_path.read_bytes()) if result_path.exists() else {}
                    except (OSError, ValueError):
                        document = {}
                    document.update({"executionId": execution_id, "caseId": case["id"],
                                     "status": "INCOMPLETE", "finishedAt": now()})
                    document.setdefault("errors", []).append(str(error))
                    atomic_json(result_path, document)
                    manifest["errors"].append({"caseId": case["id"], "message": str(error)})
            manifest["cases"].append({"id": case["id"], "status": document["status"],
                                      "result": f"caso-{case['id']}/resultado.json",
                                      "comparisonStatus": document.get("analysis", {}).get("comparison_status")})
            atomic_json(execution_folder / "manifest.json", manifest)
    except (OSError, ValueError, ValidationError, seed.InitializationError) as error:
        manifest["errors"].append({"stage": stage, "message": str(error)})
    except KeyboardInterrupt:
        manifest["interrupted"] = True
        manifest["errors"].append({"stage": stage, "message": "Interrupción solicitada; no se reintentaron envíos."})
    finally:
        represented = {entry["id"] for entry in manifest["cases"]}
        for case in selected:
            if case["id"] in represented:
                continue
            case_folder = execution_folder / ("caso-" + case["id"])
            result_path = case_folder / "resultado.json"
            if result_path.exists():
                document = json.loads(result_path.read_bytes())
                document.update({"status": "INCOMPLETE", "finishedAt": now()})
                document.setdefault("errors", []).append("La ejecución se interrumpió; se conservaron los datos ya registrados.")
                for entry in document.get("evidence", []):
                    if entry.get("httpOutcome") == "SENDING":
                        entry["httpOutcome"] = "UNCERTAIN"
                        entry["error"] = "Interrupción antes de confirmar la respuesta; no reenviar esta solicitud."
            else:
                document = {"executionId": execution_id, "caseId": case["id"], "trackId": str(uuid4()),
                            "deviceId": manifest.get("devices", {}).get(case["device"], {}).get("id"),
                            "status": "PENDING" if findings[case["id"]] else "NOT_RUN",
                            "configuration": deepcopy(case), "evidence": [], "finishedAt": now(),
                            "errors": findings[case["id"]] or ["No ejecutado por un error previo del lote."]}
            atomic_json(case_folder / "resultado.json", document)
            manifest["cases"].append({"id": case["id"], "status": document["status"],
                                      "result": f"caso-{case['id']}/resultado.json"})
        manifest["finishedAt"] = now()
        manifest["status"] = ("COMPLETED" if len(manifest["cases"]) == len(selected)
                              and not manifest["errors"]
                              and all(case["status"] == "COMPLETED" for case in manifest["cases"]) else "INCOMPLETE")
        atomic_json(execution_folder / "manifest.json", manifest)
    return manifest, execution_folder


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--run", action="store_true")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--case", dest="case_id", choices=tuple(catalog.APPROVED))
    selection.add_argument("--category", type=int, choices=tuple(catalog.COUNTS))
    selection.add_argument("--all", action="store_true")
    args = parser.parse_args(argv)
    if args.run and not (args.case_id or args.category or args.all):
        parser.error("--run requiere --case, --category o --all.")
    try:
        ensure_validation_branch()
        loaded = load_config(args.config)
        findings = inspect_cases(loaded)
        selected = select_cases(loaded["config"], args.case_id, args.category)
        if args.check:
            report = read_only_preflight(loaded, selected, findings)
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return 0 if report["ready"] else 1
        manifest, folder = run_batch(loaded, selected, findings)
        print(json.dumps({"executionId": manifest["executionId"], "status": manifest["status"],
                          "manifest": str(folder / "manifest.json"), "cases": manifest["cases"],
                          "errors": manifest["errors"]}, ensure_ascii=False, indent=2))
        return 130 if manifest.get("interrupted") else 0 if manifest["status"] == "COMPLETED" else 1
    except (ValidationError, OSError, ValueError) as error:
        print("Error de validación: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
