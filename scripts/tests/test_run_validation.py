"""Pruebas del lote y sus contratos offline, sin servicios ni inferencias reales."""

from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import gzip
import hashlib
import importlib.util
import io
from http.client import IncompleteRead
import json
from pathlib import Path
import shutil
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from uuid import uuid4


SCRIPT = Path(__file__).resolve().parents[1] / "run-validation.py"
SPEC = importlib.util.spec_from_file_location("run_validation_tests", SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runner
SPEC.loader.exec_module(runner)


STEPS = {
    "IMAGE": ("FaceRecognitionStep", "ObjectRecognitionStep"),
    "AUDIO": ("ConversationAnalysisStep",),
    "VIDEO": ("VideoAnalysisStep",),
}
SUFFIX = {"IMAGE": ".jpg", "AUDIO": ".wav", "VIDEO": ".mp4"}


class BatchFixture(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for case_id, (_, slots) in runner.catalog.APPROVED.items():
            folder = self.root / case_id.replace(".", "/")
            folder.mkdir(parents=True)
            counters = {}
            for slot in slots:
                kind = slot["media_type"]
                counters[kind] = counters.get(kind, 0) + 1
                name = kind.lower() + "_" + str(counters[kind]) + SUFFIX[kind]
                (folder / name).write_bytes((case_id + "/" + name + "/offline fixture").encode())
            if case_id in ("5.4", "5.6"):
                (folder / "image_2.jpg").write_bytes((case_id + "/image_2.jpg/offline fixture").encode())
        references = self.root / "references"
        references.mkdir()
        self.devices = {}
        for name in ("juli", "mate"):
            (references / (name + ".jpg")).write_bytes(("reference-file-" + name).encode())
            self.devices[name] = str(uuid4())
        self.seed_data = {
            "version": 1, "manager_base_url": "http://manager.invalid:8088",
            "ml_base_url": "http://ml.invalid:8000", "timeout_seconds": 5,
            "devices": [
                {"id": self.devices[name], "label": "Device " + name.title(),
                 "person_name": name.title(), "images": ["references/" + name + ".jpg"]}
                for name in ("juli", "mate")
            ],
        }
        (self.root / "seed.local.json").write_text(json.dumps(self.seed_data))
        self.config_data = runner.catalog.build_cases(self.root)
        self.config_path = self.root / "cases.json"
        self.save_config()
        self.loaded = runner.load_config(self.config_path)

    def save_config(self):
        self.config_path.write_text(json.dumps(self.config_data, ensure_ascii=False), encoding="utf-8")

    def case(self, case_id):
        return next(case for case in self.loaded["config"]["cases"] if case["id"] == case_id)

    def configured_seed(self):
        config = runner.seed.load_config(self.root / "seed.local.json")
        self.loaded["seed"] = config
        return config

    def device(self, case):
        seed = self.configured_seed()
        return runner.device_mapping(self.loaded, seed)[case["device"]]

    def source_hashes(self):
        return {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                for category in range(1, 7) for path in (self.root / str(category)).glob("*/*")
                if path.is_file()}

    def event_transport(self, case, calls, *, uncertain=False, write_audit=False):
        """Contrato del Manager y archivos de auditoría; no existe un endpoint ML sustituto."""
        def submit(path, payload, media_type, device_id, track_id, base_url, timeout):
            index = len(calls)
            evidence = case["evidence"][index % len(case["evidence"])]
            request_id = str(uuid4())
            calls.append({"path": path, "payload": payload, "media_type": media_type,
                          "device_id": device_id, "track_id": track_id,
                          "request_id": request_id, "evidence": evidence})
            if uncertain:
                return {"httpStatus": None, "response": None, "validationAudit": None,
                        "error": "timeout after request was sent", "elapsedMs": 1}
            if write_audit:
                self.write_audit(case, evidence, request_id, track_id, device_id, payload)
            return {
                "httpStatus": 201, "error": None, "elapsedMs": 2,
                "startedAt": "2026-10-30T10:00:00+00:00", "finishedAt": "2026-10-30T10:00:01+00:00",
                "validationAudit": {"requestId": request_id, "status": "RECORDED",
                                    "validForQuantitativeValidation": True},
                "response": {"data": {"trackId": track_id, "chunkType": media_type,
                                      "finalVerdict": "DANGER", "finalSeverity": case["expected_final_severity"]}},
            }
        return submit

    def audit_result(self, case, evidence, request_id, track_id, device_id):
        expected = [entry["signal"] for entry in evidence["expected_signals"]]
        record = {"requestId": request_id, "trackId": track_id, "deviceId": device_id,
                  "evidenceType": evidence["media_type"], "httpStatus": 201,
                  "auditStatus": "RECORDED", "analysisStatus": "COMPLETE",
                  "validForQuantitativeValidation": True,
                  "steps": [{"requestId": request_id, "step": step, "quality": "VALID", "signals": expected}
                            for step in STEPS[evidence["media_type"]]],
                  "consolidation": {"trackId": track_id, "accumulatedSignals": expected,
                                    "severity": case["expected_final_severity"], "verdict": "DANGER"}}
        return {"record": record, "path": request_id + ".json", "artifacts": [], "raw_results": [],
                "integrity_errors": [], "analysis_status": "COMPLETE", "audit_status": "RECORDED",
                "valid_for_quantitative_validation": True}

    def retrieve_fixture(self, case):
        def retrieve(loaded, destination, evidence, response, track_id, device_id, input_hash):
            request_id = (response.get("validationAudit") or {}).get("requestId", str(uuid4()))
            if response.get("httpStatus") is None:
                return {"record": None, "integrity_errors": ["response uncertain"],
                        "raw_results": [], "valid_for_quantitative_validation": False}
            return self.audit_result(case, evidence, request_id, track_id, device_id)
        return retrieve

    def write_audit(self, case, evidence, request_id, track_id, device_id, payload):
        track = self.loaded["paths"]["audit_directory"] / track_id
        track.mkdir(parents=True, exist_ok=True)
        record = self.audit_result(case, evidence, request_id, track_id, device_id)["record"]
        record.update({"sha256": hashlib.sha256(payload).hexdigest(), "filename": evidence["file"],
                       "expectedSteps": list(STEPS[evidence["media_type"]]), "mlResponses": []})
        for number, step in enumerate(STEPS[evidence["media_type"]], 1):
            body = json.dumps({"diagnostic": "offline audit fixture", "retainedField": number}).encode()
            compressed = gzip.compress(body)
            relative = request_id + "/ml-" + str(number).zfill(3) + ".json.gz"
            path = track / relative
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(compressed)
            record["mlResponses"].append({
                "requestId": request_id, "step": step, "endpoint": "/diagnostic/" + step,
                "httpStatus": 200, "path": relative, "bodyRepresentation": "ORIGINAL_BYTES",
                "originalSha256": hashlib.sha256(body).hexdigest(), "originalByteLength": len(body),
                "storedSha256": hashlib.sha256(compressed).hexdigest(), "storedByteLength": len(compressed),
            })
        (track / (request_id + ".json")).write_text(json.dumps(record), encoding="utf-8")


class BatchConfigurationTest(BatchFixture):
    def test_approved_catalog_has_40_distinct_cases_and_device_assignments(self):
        findings = runner.inspect_cases(self.loaded)
        self.assertEqual(len(findings), 40)
        self.assertTrue(all(not issues for issues in findings.values()), findings)
        for case in self.loaded["config"]["cases"]:
            category = case["category"]
            self.assertEqual(case["expected_final_severity"], runner.catalog.APPROVED[case["id"]][0])
            self.assertEqual(case["person"], "juli" if category <= 3 else "mate")
            self.assertEqual(case["expected_known"], case["person"] == case["device"])
            self.assertNotIn("deviceId", case)
        for case_id in ("4.2", "5.3", "6.4"):
            self.assertEqual(self.case(case_id)["device"], "mate")

    def test_rejects_non_validation_branch(self):
        with patch.object(runner.subprocess, "check_output", return_value="master\n"):
            with self.assertRaisesRegex(runner.ValidationError, "validacion"):
                runner.ensure_validation_branch()

    def test_run_requires_an_explicit_selector(self):
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            runner.main(["--run", "--config", str(self.config_path)])
        self.assertEqual(error.exception.code, 2)

    def test_rejects_missing_or_duplicate_case_ids(self):
        for change in (lambda rows: rows.pop(), lambda rows: rows.__setitem__(1, deepcopy(rows[0]))):
            with self.subTest(change=change):
                config = deepcopy(self.config_data)
                change(config["cases"])
                self.config_path.write_text(json.dumps(config))
                with self.assertRaises(runner.ValidationError):
                    runner.load_config(self.config_path)

    def test_reports_unapproved_severity_person_or_device(self):
        case = self.case("5.3")
        case["expected_final_severity"] = "NORMAL"
        case["device"] = "juli"
        case["person"] = "juli"
        issues = runner.inspect_cases(self.loaded)["5.3"]
        self.assertTrue(any("severidad" in issue for issue in issues))
        self.assertTrue(any("Persona/dispositivo" in issue for issue in issues))

    def test_report_expectations_that_do_not_match_approved_signal_conditions(self):
        case = self.case("4.1")
        video = next(entry for entry in case["evidence"] if entry["media_type"] == "VIDEO")
        video["expected_signals"][0]["condition"] = "VALID_ANALYSIS"
        self.assertTrue(runner.inspect_cases(self.loaded)["4.1"])

    def test_catalog_version_boolean_is_not_accepted_as_integer_one(self):
        self.config_data["version"] = True
        self.save_config()
        with self.assertRaises(runner.ValidationError):
            runner.load_config(self.config_path)

    def test_malformed_case_identifiers_report_validation_error_without_type_crashes(self):
        for value in ([], {}, None, 1):
            with self.subTest(value=value):
                config = deepcopy(self.config_data)
                config["cases"][0]["id"] = value
                self.config_path.write_text(json.dumps(config))
                with self.assertRaises(runner.ValidationError):
                    runner.load_config(self.config_path)

    def test_malformed_evidence_ids_and_media_types_are_pending_without_type_crashes(self):
        case = self.case("1.1")
        original = deepcopy(case["evidence"][0])
        for field in ("id", "media_type"):
            with self.subTest(field=field):
                case["evidence"][0] = deepcopy(original)
                case["evidence"][0][field] = []
                self.assertTrue(runner.inspect_cases(self.loaded)["1.1"])

    def test_motion_sufficiency_requires_explicit_boolean_annotations(self):
        video = next(entry for entry in self.case("4.1")["evidence"] if entry["media_type"] == "VIDEO")
        for invalid in (1, "true", [], {"LONG_STOP": "true"}):
            with self.subTest(annotation=invalid):
                video["sufficient_motion"] = invalid
                self.assertTrue(any("sufficient_motion" in issue
                                    for issue in runner.inspect_cases(self.loaded)["4.1"]))
        for valid in (True, False, {"LONG_STOP": True, "PROLONGED_PRESENCE": False}):
            with self.subTest(annotation=valid):
                video["sufficient_motion"] = valid
                self.assertEqual(runner.inspect_cases(self.loaded)["4.1"], [])

    def test_second_image_manual_expectations_cannot_be_swapped_with_first(self):
        case = self.case("6.4")
        case["evidence"][0], case["evidence"][1] = case["evidence"][1], case["evidence"][0]
        self.assertTrue(any("condicionamientos" in issue
                            for issue in runner.inspect_cases(self.loaded)["6.4"]))

    def test_approved_weapon_visual_category_cannot_be_silently_changed(self):
        case = self.case("5.4")
        case["evidence"][0]["expected_visual_categories"] = ["Melee_Weapon"]
        self.assertTrue(any("categorías visuales" in issue
                            for issue in runner.inspect_cases(self.loaded)["5.4"]))

    def test_device_alias_cannot_select_the_other_person_from_seed(self):
        seed = self.configured_seed()
        self.loaded["config"]["device_aliases"] = {"juli": "Device Mate", "mate": "Device Juli"}
        with self.assertRaisesRegex(runner.ValidationError, "persona equivocada"):
            runner.device_mapping(self.loaded, seed)

    def test_device_ids_are_loaded_from_seed_without_catalog_duplication(self):
        seed = self.configured_seed()
        mapping = runner.device_mapping(self.loaded, seed)
        self.assertEqual(mapping["juli"].id, self.devices["juli"])
        self.assertEqual(mapping["mate"].id, self.devices["mate"])
        encoded = self.config_path.read_text()
        self.assertNotIn(self.devices["juli"], encoded)
        self.assertNotIn(self.devices["mate"], encoded)

    def test_rejects_private_result_path_inside_tracked_source_tree(self):
        self.config_data["results_directory"] = str(runner.REPOSITORY / "scripts/private-results")
        self.save_config()
        with self.assertRaisesRegex(runner.ValidationError, "validation-data"):
            runner.load_config(self.config_path)

    def test_rejects_both_directions_of_audit_results_overlap(self):
        for audit, results in (("resultados/audit", "resultados"), ("audit", "audit/resultados")):
            with self.subTest(audit=audit, results=results):
                config = deepcopy(self.config_data)
                config.update({"audit_directory": audit, "results_directory": results})
                self.config_path.write_text(json.dumps(config))
                with self.assertRaises(runner.ValidationError):
                    runner.load_config(self.config_path)

    def test_path_reuse_and_traversal_are_reported_without_loading_media(self):
        case = self.case("1.1")
        case["evidence"].append(deepcopy(case["evidence"][0]))
        issues = runner.inspect_cases(self.loaded)["1.1"]
        self.assertTrue(any("reutilizada" in issue for issue in issues))
        case["evidence"][0]["file"] = "../../2/1/image_1.jpg"
        issues = runner.inspect_cases(self.loaded)["1.1"]
        self.assertTrue(any("fuera" in issue for issue in issues))

    def test_symbolic_links_to_other_case_are_not_accepted(self):
        case = self.case("1.1")
        path = self.root / case["folder"] / case["evidence"][0]["file"]
        path.unlink()
        path.symlink_to(self.root / "2/1/image_1.jpg")
        issues = runner.inspect_cases(self.loaded)["1.1"]
        self.assertTrue(any("enlaces" in issue for issue in issues))

    def test_association_reads_only_names_extensions_and_metadata(self):
        with patch.object(Path, "read_bytes", side_effect=AssertionError("No leer contenido de evidencias")):
            findings = runner.inspect_cases(self.loaded)
        self.assertTrue(all(not issues for issues in findings.values()))


class BatchCheckTest(BatchFixture):
    def test_check_with_empty_h2_only_reads_services_and_reports_pending_initialization(self):
        calls = []

        class Response:
            def __init__(self, url, body):
                self.url = url
                self.body = json.dumps(body).encode()

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def geturl(self):
                return self.url

            def read(self):
                return self.body

        def read_only(request, timeout):
            calls.append((request.get_method(), request.full_url))
            self.assertEqual(request.get_method(), "GET")
            if request.full_url.endswith("/v3/api-docs"):
                return Response(request.full_url, {"paths": {
                    runner.seed.MANAGER_API + "/devices": {},
                    runner.seed.MANAGER_API + "/devices/{deviceId}/persons": {},
                    runner.seed.MANAGER_API + "/devices/{deviceId}/persons/{personId}/face-embeddings": {},
                }})
            if request.full_url.endswith("/health"):
                return Response(request.full_url, {"status": "ok"})
            if request.full_url.endswith("/persons"):
                return Response(request.full_url, {"data": []})
            raise HTTPError(request.full_url, 404, "not found", {}, io.BytesIO(b"{}"))

        with patch.object(runner, "ensure_validation_branch"), \
                patch.object(runner.seed, "urlopen", side_effect=read_only), \
                patch.object(runner.seed, "initialize") as initialize, \
                patch.object(runner.sender, "submit_event") as submit, \
                redirect_stdout(io.StringIO()) as output:
            code = runner.main(["--config", str(self.config_path), "--check", "--case", "5.3"])
        self.assertEqual(code, 1)
        report = json.loads(output.getvalue())
        self.assertTrue(report["servicesAvailable"])
        self.assertTrue(report["initializationPending"])
        initialize.assert_not_called()
        submit.assert_not_called()
        self.assertTrue(calls)
        self.assertFalse((self.root / "resultados").exists())

    def test_check_with_valid_existing_state_never_initializes_or_submits(self):
        states = [SimpleNamespace(device={}, person={}) for _ in range(2)]
        with patch.object(runner, "ensure_validation_branch"), \
                patch.object(runner.seed.ApiClient, "check_services") as services, \
                patch.object(runner.seed, "build_plan", return_value=states), \
                patch.object(runner.seed, "initialize") as initialize, \
                patch.object(runner.sender, "submit_event") as submit, \
                redirect_stdout(io.StringIO()) as output:
            code = runner.main(["--config", str(self.config_path), "--check", "--category", "5"])
        self.assertEqual(code, 0)
        self.assertEqual(len(json.loads(output.getvalue())["cases"]), 8)
        services.assert_called_once()
        initialize.assert_not_called()
        submit.assert_not_called()


class BatchExecutionTest(BatchFixture):
    def execute_with_fixture(self, case_id):
        case = self.case(case_id)
        calls = []
        folder = self.root / "execution"
        folder.mkdir()
        with patch.object(runner.sender, "submit_event", side_effect=self.event_transport(case, calls)), \
                patch.object(runner, "retrieve_audit", side_effect=self.retrieve_fixture(case)):
            result = runner.execute_case(self.loaded, case, self.device(case), str(uuid4()), folder)
        return case, calls, result, folder

    def test_danger_does_not_stop_video_image_image_audio_case(self):
        case, calls, result, _ = self.execute_with_fixture("5.6")
        self.assertEqual([call["media_type"] for call in calls], ["VIDEO", "IMAGE", "IMAGE", "AUDIO"])
        self.assertEqual([call["path"].name for call in calls], [entry["file"] for entry in case["evidence"]])
        self.assertEqual(len({call["track_id"] for call in calls}), 1)
        self.assertEqual(len({call["device_id"] for call in calls}), 1)
        self.assertEqual(result["status"], "COMPLETED")

    def test_two_image_case_preserves_distinct_explicit_file_order(self):
        case, calls, result, _ = self.execute_with_fixture("6.4")
        self.assertEqual([call["media_type"] for call in calls], ["IMAGE", "IMAGE", "AUDIO"])
        self.assertNotEqual(calls[0]["path"], calls[1]["path"])
        self.assertEqual([call["path"].name for call in calls], [entry["file"] for entry in case["evidence"]])
        self.assertEqual(result["status"], "COMPLETED")

    def test_uncertain_http_is_not_retried_and_remaining_evidence_is_saved_not_sent(self):
        case = self.case("5.3")
        calls = []
        folder = self.root / "execution"
        folder.mkdir()
        with patch.object(runner.sender, "submit_event", side_effect=self.event_transport(case, calls, uncertain=True)), \
                patch.object(runner, "retrieve_audit", side_effect=self.retrieve_fixture(case)):
            result = runner.execute_case(self.loaded, case, self.device(case), str(uuid4()), folder)
        self.assertEqual(len(calls), 1)
        self.assertEqual([entry["httpOutcome"] for entry in result["evidence"]], ["UNCERTAIN", "NOT_SENT"])
        self.assertEqual(result["status"], "INCOMPLETE")
        self.assertFalse(result["analysis"]["processing_complete"])

    def test_read_failure_after_created_status_is_uncertain_and_never_valid(self):
        case = self.case("5.3")
        calls = []
        folder = self.root / "execution"
        folder.mkdir()
        transport = self.event_transport(case, calls)

        def body_read_failed(*args):
            response = transport(*args)
            response["error"] = "response body interrupted after HTTP status"
            return response

        with patch.object(runner.sender, "submit_event", side_effect=body_read_failed), \
                patch.object(runner, "retrieve_audit", side_effect=self.retrieve_fixture(case)):
            result = runner.execute_case(self.loaded, case, self.device(case), str(uuid4()), folder)
        self.assertEqual(len(calls), 1)
        self.assertEqual(result["evidence"][0]["httpStatus"], 201)
        self.assertEqual(result["evidence"][0]["httpOutcome"], "UNCERTAIN")
        self.assertFalse(result["evidence"][0]["audit"]["valid_for_quantitative_validation"])
        self.assertEqual(result["status"], "INCOMPLETE")

    def test_pending_case_is_saved_without_initialization_or_evidence_submission(self):
        case = self.case("6.4")
        findings = runner.inspect_cases(self.loaded)
        findings[case["id"]] = ["Falta configurar el orden"]
        with patch.object(runner.seed, "initialize") as initialize, \
                patch.object(runner.sender, "submit_event") as submit:
            manifest, folder = runner.run_batch(self.loaded, [case], findings)
        self.assertEqual(manifest["status"], "INCOMPLETE")
        result = json.loads((folder / "caso-6.4/resultado.json").read_text())
        self.assertEqual(result["status"], "PENDING")
        self.assertEqual(result["evidence"], [])
        initialize.assert_not_called()
        submit.assert_not_called()

    def test_batch_initialization_failure_keeps_manifest_and_snapshots(self):
        case = self.case("1.1")
        findings = runner.inspect_cases(self.loaded)
        with patch.object(runner.seed, "initialize", side_effect=runner.seed.InitializationError("initialization failed")) as initialize, \
                patch.object(runner.sender, "submit_event") as submit:
            manifest, folder = runner.run_batch(self.loaded, [case], findings)
        self.assertEqual(manifest["status"], "INCOMPLETE")
        self.assertEqual(manifest["errors"][0]["stage"], "INITIALIZATION")
        self.assertEqual((folder / "cases.snapshot.json").read_bytes(), self.loaded["encoded"])
        self.assertEqual((folder / "seed.snapshot.json").read_bytes(), (self.root / "seed.local.json").read_bytes())
        self.assertTrue((folder / "manifest.json").is_file())
        not_run = json.loads((folder / "caso-1.1/resultado.json").read_text())
        self.assertEqual(not_run["status"], "NOT_RUN")
        self.assertEqual(not_run["evidence"], [])
        initialize.assert_called_once()
        submit.assert_not_called()

    def test_unexpected_case_failure_still_publishes_incomplete_case_result(self):
        case = self.case("1.1")
        findings = runner.inspect_cases(self.loaded)
        with patch.object(runner.seed, "initialize"), \
                patch.object(runner, "execute_case", side_effect=OSError("destination failure")):
            manifest, folder = runner.run_batch(self.loaded, [case], findings)
        self.assertEqual(manifest["status"], "INCOMPLETE")
        document = json.loads((folder / "caso-1.1/resultado.json").read_text())
        self.assertEqual(document["status"], "INCOMPLETE")
        self.assertIn("destination failure", str(document["errors"]))

    def test_each_batch_and_case_uses_fresh_uuids_without_overwriting_results(self):
        case = self.case("1.1")
        findings = runner.inspect_cases(self.loaded)
        calls = []
        batches = []
        with patch.object(runner.seed, "initialize") as initialize, \
                patch.object(runner.sender, "submit_event", side_effect=self.event_transport(case, calls)), \
                patch.object(runner, "retrieve_audit", side_effect=self.retrieve_fixture(case)):
            for _ in range(2):
                batches.append(runner.run_batch(self.loaded, [case], findings))
        self.assertNotEqual(batches[0][0]["executionId"], batches[1][0]["executionId"])
        self.assertNotEqual(calls[0]["track_id"], calls[1]["track_id"])
        self.assertNotEqual(batches[0][1], batches[1][1])
        self.assertEqual(initialize.call_count, 2)
        for manifest, folder in batches:
            self.assertEqual(manifest["status"], "COMPLETED")
            self.assertTrue((folder / "caso-1.1/resultado.json").exists())

    def test_result_archive_is_independent_and_does_not_modify_source_evidence(self):
        case = self.case("5.3")
        calls = []
        before = self.source_hashes()
        folder = self.root / "execution"
        folder.mkdir()
        with patch.object(runner.sender, "submit_event", side_effect=self.event_transport(case, calls, write_audit=True)):
            result = runner.execute_case(self.loaded, case, self.device(case), str(uuid4()), folder)
        self.assertEqual(result["status"], "COMPLETED")
        destination = folder / "caso-5.3/auditorias"
        self.assertEqual(len(list(destination.glob("*.json"))), 2)
        self.assertEqual(len(list(destination.glob("*/*.json.gz"))), 3)
        for entry in result["evidence"]:
            self.assertNotIn("record", entry["audit"])
            for raw in entry["audit"]["raw_results"]:
                self.assertNotIn("body", raw)
            for artifact in entry["audit"]["artifacts"]:
                self.assertEqual(hashlib.sha256((destination / artifact["path"]).read_bytes()).hexdigest(), artifact["sha256"])
        shutil.rmtree(self.loaded["paths"]["audit_directory"])
        self.assertEqual(len(list(destination.glob("*.json"))), 2)
        self.assertEqual(len(list(destination.glob("*/*.json.gz"))), 3)
        self.assertEqual(self.source_hashes(), before)
        self.assertFalse(list(folder.rglob(".batch-*")))

    def test_truncated_http_body_is_recorded_uncertain_without_retry(self):
        case = self.case("5.3")
        folder = self.root / "execution"
        folder.mkdir()
        with patch.object(runner.sender, "submit_event", side_effect=IncompleteRead(b'{"data":', 20)) as submit, \
                patch.object(runner, "retrieve_audit", side_effect=self.retrieve_fixture(case)):
            result = runner.execute_case(self.loaded, case, self.device(case), str(uuid4()), folder)
        submit.assert_called_once()
        self.assertEqual(result["status"], "INCOMPLETE")
        self.assertEqual([item["httpOutcome"] for item in result["evidence"]], ["UNCERTAIN", "NOT_SENT"])
        self.assertIn("IncompleteRead", result["evidence"][0]["error"])

    def test_interruption_preserves_completed_evidence_and_pending_request_hash(self):
        case = self.case("5.3")
        following = self.case("1.1")
        calls = []
        transport = self.event_transport(case, calls, write_audit=True)

        def interrupt_audio(*args):
            if args[2] == "AUDIO":
                raise KeyboardInterrupt()
            return transport(*args)

        with patch.object(runner.seed, "initialize"), \
                patch.object(runner.sender, "submit_event", side_effect=interrupt_audio):
            manifest, folder = runner.run_batch(self.loaded, [case, following], runner.inspect_cases(self.loaded))
        self.assertTrue(manifest["interrupted"])
        self.assertEqual(manifest["status"], "INCOMPLETE")
        document = json.loads((folder / "caso-5.3/resultado.json").read_text())
        self.assertEqual(document["trackId"], calls[0]["track_id"])
        self.assertEqual(len(document["evidence"]), 2)
        self.assertEqual(document["evidence"][0]["httpOutcome"], "ACCEPTED")
        self.assertEqual(document["evidence"][1]["httpOutcome"], "UNCERTAIN")
        audio = self.root / case["folder"] / case["evidence"][1]["file"]
        self.assertEqual(document["evidence"][1]["input_sha256"], hashlib.sha256(audio.read_bytes()).hexdigest())
        self.assertEqual(len(list((folder / "caso-5.3/auditorias").glob("*.json"))), 1)
        self.assertEqual(json.loads((folder / "caso-1.1/resultado.json").read_text())["status"], "NOT_RUN")
        with patch.object(runner, "run_batch", return_value=(manifest, folder)), redirect_stdout(io.StringIO()):
            self.assertEqual(runner.main(["--config", str(self.config_path), "--run", "--case", "5.3"]), 130)


if __name__ == "__main__":
    unittest.main()
