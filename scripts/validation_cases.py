"""Catálogo aprobado y asociación de evidencias por nombres, extensiones y metadatos.

No abre contenido multimedia, no consulta servicios y no infiere expectativas desde Java.
La configuración con nombres reales de archivos se guarda únicamente en validation-data.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
import re
import sys


EXTENSIONS = {
    "IMAGE": {".jpg", ".jpeg", ".png", ".webp", ".bmp"},
    "AUDIO": {".wav", ".mp3", ".m4a", ".ogg", ".flac"},
    "VIDEO": {".mp4", ".mov", ".avi", ".mkv", ".webm"},
}

COUNTS = {1: 7, 2: 8, 3: 6, 4: 7, 5: 8, 6: 4}
MOTION_SIGNALS = {
    "APPROACHING_CAMERA", "FAST_APPROACH", "PROLONGED_PRESENCE", "LONG_STOP",
    "PROLONGED_VISIT_PRESENCE", "REPEATED_DIRECTION_CHANGES",
    "RETURNED_TO_PREVIOUS_POSITION", "LOITERING", "MULTIPLE_PEOPLE",
}


def _slot(media_type, *signals, manual=(), notes=(), visual=()):
    expectations = []
    for signal in signals:
        condition = "VALID_ANALYSIS"
        if media_type == "VIDEO" and signal in MOTION_SIGNALS:
            condition = "SUFFICIENT_MOTION"
        if signal in manual:
            condition = "MANUAL_REVIEW"
        expectations.append({
            "signal": signal,
            "condition": condition,
            "scope": "modality" if media_type == "VIDEO" else "evidence",
        })
    return {
        "media_type": media_type,
        "expected_signals": expectations,
        "unjustified_signals": [],
        "expected_visual_categories": list(visual),
        "review_notes": list(notes),
    }


def _image(*signals, **kwargs):
    return _slot("IMAGE", *signals, **kwargs)


def _audio(*signals, **kwargs):
    return _slot("AUDIO", *signals, **kwargs)


def _video(*signals, **kwargs):
    return _slot("VIDEO", *signals, **kwargs)


# Única fuente semántica: las fichas y la tabla entregadas por el usuario.
# El caso 3.4 fue excluido por indicación del usuario; se conservan los otros IDs.
# Las listas vacías de señales injustificadas significan que no se suministraron criterios.
APPROVED = {
    "1.1": ("NORMAL", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT")]),
    "1.2": ("NORMAL", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT")]),
    "1.3": ("NORMAL", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT")]),
    "1.4": ("NORMAL", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT")]),
    "1.5": ("NORMAL", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT"), _audio("NO_SUSPICIOUS_CONVERSATION")]),
    "1.6": ("NORMAL", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT"), _video("APPROACHING_CAMERA")]),
    "1.7": ("NORMAL", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT", "PACKAGE_DETECTED"), _video("NO_SUSPICIOUS_BEHAVIOR"), _audio("NO_SUSPICIOUS_CONVERSATION")]),

    "2.1": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT")]),
    "2.2": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT")]),
    "2.3": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT", "PACKAGE_DETECTED"), _audio("NO_SUSPICIOUS_CONVERSATION")]),
    "2.4": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _audio("NO_SUSPICIOUS_CONVERSATION")]),
    "2.5": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _audio("NO_SUSPICIOUS_CONVERSATION")]),
    "2.6": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _video("APPROACHING_CAMERA")]),
    "2.7": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT", "PACKAGE_DETECTED"), _video("NO_SUSPICIOUS_BEHAVIOR")]),
    "2.8": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _video("APPROACHING_CAMERA"), _audio("NO_SUSPICIOUS_CONVERSATION")]),

    "3.1": ("SOSPECHOSO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _audio("AUTHORITY", "VERIFICATION_AVOIDANCE")]),
    "3.2": ("SOSPECHOSO", [_audio("MONEY_REQUEST", "URGENCY")]),
    "3.3": ("SOSPECHOSO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT", "PACKAGE_DETECTED"), _audio("CODE_REQUEST")]),
    "3.5": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _audio("NO_SUSPICIOUS_CONVERSATION")]),
    "3.6": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT", "PACKAGE_DETECTED"), _audio("MONEY_REQUEST")]),
    "3.7": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _audio("URGENCY")]),

    "4.1": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _video("PROLONGED_PRESENCE", "LONG_STOP", "PROLONGED_VISIT_PRESENCE")]),
    "4.2": ("NORMAL", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT"), _video("PROLONGED_PRESENCE", "LONG_STOP", "PROLONGED_VISIT_PRESENCE")]),
    "4.3": ("SOSPECHOSO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _video("PROLONGED_PRESENCE", "PROLONGED_VISIT_PRESENCE", "REPEATED_DIRECTION_CHANGES", "RETURNED_TO_PREVIOUS_POSITION", "LOITERING")]),
    "4.4": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _video("RETURNED_TO_PREVIOUS_POSITION")]),
    "4.5": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT", "PACKAGE_DETECTED"), _video("APPROACHING_CAMERA", "FAST_APPROACH"), _audio("NO_SUSPICIOUS_CONVERSATION")]),
    "4.6": ("SOSPECHOSO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _video("APPROACHING_CAMERA", "FAST_APPROACH"), _audio("PRESSURE", "URGENCY")]),
    "4.7": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "MULTIPLE_PEOPLE", "NO_THREAT_OBJECT"), _video("MULTIPLE_PEOPLE"), _audio("NO_SUSPICIOUS_CONVERSATION")]),

    "5.1": ("PELIGROSO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _audio("THREAT")]),
    "5.2": ("PELIGROSO", [_audio("THREAT", "MONEY_REQUEST", "PRESSURE")]),
    "5.3": ("PELIGROSO", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT"), _audio("THREAT")]),
    "5.4": ("PELIGROSO", [_image("UNKNOWN_PERSON", "WEAPON_DETECTED", visual=("Firearm",))]),
    "5.5": ("PELIGROSO", [_image("UNKNOWN_PERSON", "WEAPON_DETECTED", visual=("Melee_Weapon",))]),
    "5.6": ("PELIGROSO", [_video("APPROACHING_CAMERA", "FAST_APPROACH"), _image("UNKNOWN_PERSON", "WEAPON_DETECTED"), _audio("THREAT", "PRESSURE", "URGENCY")]),
    "5.7": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT", "PACKAGE_DETECTED"), _audio("NO_SUSPICIOUS_CONVERSATION")]),
    "5.8": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "TOOL_DETECTED", "NO_THREAT_OBJECT"), _audio("NO_SUSPICIOUS_CONVERSATION")]),

    "6.1": ("SOSPECHOSO", [_image("COVERED_FACE")]),
    "6.2": ("SOSPECHOSO", [_image("COVERED_FACE", manual=("COVERED_FACE",), notes=("Cobertura facial a evaluar; no exigir COVERED_FACE automáticamente.",)), _video("LOITERING", notes=("Movimientos observables sujetos a revisión; no se especificaron otras señales obligatorias.",)), _audio("VERIFICATION_AVOIDANCE")]),
    "6.3": ("LLAMATIVO", [_image("UNKNOWN_PERSON", "NO_THREAT_OBJECT"), _audio("NO_SUSPICIOUS_CONVERSATION")]),
    "6.4": ("NORMAL", [_image("KNOWN_PERSON", "NO_THREAT_OBJECT"), _image("COVERED_FACE", manual=("COVERED_FACE",), notes=("Evaluar cobertura y viabilidad facial de la segunda imagen; no exigir COVERED_FACE automáticamente.",)), _audio("NO_SUSPICIOUS_CONVERSATION")]),
}


def media_type(path):
    for kind, extensions in EXTENSIONS.items():
        if path.suffix.lower() in extensions:
            return kind
    return None


def _numbered_order(paths):
    """Sólo ordena secuencias inequívocas; no usa orden alfabético como sustituto."""
    if len(paths) <= 1:
        return list(paths)
    trailing = [re.fullmatch(r"(.*?)(\d+)", path.stem) for path in paths]
    if all(trailing):
        prefixes = {match.group(1).casefold() for match in trailing}
        numbers = [int(match.group(2)) for match in trailing]
        if len(prefixes) == 1 and len(set(numbers)) == len(numbers):
            return [path for _, path in sorted(zip(numbers, paths), key=lambda entry: entry[0])]
    leading = [re.fullmatch(r"(\d+)[ _.-]+.+", path.stem) for path in paths]
    if all(leading):
        numbers = [int(match.group(1)) for match in leading]
        if len(set(numbers)) == len(numbers):
            return [path for _, path in sorted(zip(numbers, paths), key=lambda entry: entry[0])]
    return None


def _device(case_id, category):
    if category == 1:
        return "juli"
    if category in (2, 3):
        return "mate"
    return "mate" if case_id in ("4.2", "5.3", "6.4") else "juli"


def _case_configuration(root, case_id, severity, slots):
    category, number = (int(value) for value in case_id.split("."))
    folder = root / str(category) / str(number)
    person = "juli" if category <= 3 else "mate"
    device = _device(case_id, category)
    case = {
        "id": case_id,
        "category": category,
        "folder": f"{category}/{number}",
        "person": person,
        "device": device,
        "expected_known": person == device,
        "expected_final_severity": severity,
        "configuration_status": "READY",
        "configuration_issues": [],
        "unjustified_signals": [],
        "unjustified_signals_note": "No se proporcionaron señales injustificadas explícitas; las adicionales requieren revisión contextual.",
        "evidence": [],
        "inventory": [],
        "ignored_files": [],
    }
    grouped = {kind: [] for kind in EXTENSIONS}
    if folder.is_symlink() or folder.parent.is_symlink():
        case["configuration_issues"].append("La carpeta del caso o su categoría es un enlace simbólico; no se reutilizan rutas de otras carpetas.")
    elif not folder.is_dir():
        case["configuration_issues"].append("La carpeta del caso no existe.")
    else:
        for path in sorted(folder.iterdir(), key=lambda value: value.name):
            if path.name == ".DS_Store":
                continue
            if path.is_symlink():
                case["configuration_issues"].append(f"No se asocia el enlace simbólico {path.name}.")
                continue
            if not path.is_file():
                continue
            kind = media_type(path)
            if kind is None:
                case["ignored_files"].append(path.name)
                continue
            stat = path.stat()
            if stat.st_size == 0:
                case["configuration_issues"].append(f"El archivo multimedia {path.name} está vacío.")
            case["inventory"].append({
                "file": path.name, "media_type": kind,
                "size_bytes": stat.st_size, "mtime_ns": stat.st_mtime_ns,
            })
            grouped[kind].append(path)
    planned_types = [slot["media_type"] for slot in slots]
    ordered = {}
    for kind, paths in grouped.items():
        if kind not in planned_types:
            if paths:
                case["configuration_issues"].append(f"Hay archivos {kind} pero el caso aprobado no prevé esa modalidad.")
            continue
        count = planned_types.count(kind)
        if not paths:
            case["configuration_issues"].append(f"Faltan archivos {kind}.")
            ordered[kind] = None
        elif count > 1 and len(paths) != count:
            case["configuration_issues"].append(f"Se requieren {count} archivos {kind} distintos y hay {len(paths)}; falta configurar su correspondencia.")
            ordered[kind] = None
        else:
            sequence = _numbered_order(paths)
            if sequence is None:
                case["configuration_issues"].append(f"Orden ambiguo de archivos {kind}; configurar el orden explícitamente.")
            ordered[kind] = sequence

    counters = {kind: 0 for kind in EXTENSIONS}
    for slot in slots:
        kind = slot["media_type"]
        sequence = ordered.get(kind)
        explicit_slots = planned_types.count(kind)
        if not sequence:
            assigned = [None]
        elif explicit_slots > 1:
            assigned = [sequence[counters[kind]]]
        else:
            assigned = sequence
        for path in assigned:
            counters[kind] += 1
            evidence = deepcopy(slot)
            evidence["id"] = f"{kind.lower()}-{counters[kind]}"
            evidence["file"] = path.name if path is not None else None
            case["evidence"].append(evidence)
    if case["configuration_issues"]:
        case["configuration_status"] = "PENDING"
    return case


def build_cases(root):
    """Construye los casos activos usando sólo el inventario de su propia carpeta."""
    root = Path(root).expanduser().resolve()
    cases = [
        _case_configuration(root, case_id, severity, deepcopy(slots))
        for case_id, (severity, slots) in APPROVED.items()
    ]
    return {
        "version": 1,
        "evidence_root": ".",
        "seed_config": "seed.local.json",
        "audit_directory": "audit",
        "results_directory": "resultados",
        "timeout_seconds": 300,
        "expectations_source": "40 casos activos de las fichas aprobadas; 3.4 excluido por indicación del usuario. Expectativas no inferidas de las reglas implementadas.",
        "cases": cases,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1] / "validation-data")
    parser.add_argument("--output", type=Path, help="Archivo de configuración privado; por defecto <root>/cases.json.")
    parser.add_argument("--force", action="store_true", help="Permitir reemplazar una configuración existente; revisar antes las ediciones manuales.")
    args = parser.parse_args(argv)
    root = args.root.expanduser().resolve()
    output = args.output.expanduser().resolve() if args.output else root / "cases.json"
    try:
        if output.exists() and not args.force:
            raise ValueError("La configuración ya existe. No se reemplazaron sus asociaciones manuales; usar --force sólo después de revisarlas.")
        config = build_cases(root)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("w" if args.force else "x", encoding="utf-8") as stream:
            json.dump(config, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        ready = sum(case["configuration_status"] == "READY" for case in config["cases"])
        print(f"Configurados {len(config['cases'])} casos: {ready} listos y {len(config['cases']) - ready} pendientes. {output}")
        for case in config["cases"]:
            if case["configuration_status"] == "PENDING":
                print(f"Caso {case['id']}: {'; '.join(case['configuration_issues'])}")
        return 0
    except (OSError, ValueError) as error:
        print(f"Error de catálogo: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
