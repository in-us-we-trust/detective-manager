"""Auditorías y comparaciones offline; los datos no reemplazan endpoints ni modelos."""

from copy import deepcopy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from uuid import uuid4


SPEC = importlib.util.spec_from_file_location(
    "validation_results", Path(__file__).resolve().parents[1] / "validation_results.py")
results = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(results)


class AuditCollectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.audit = self.root / "audit"
        self.destination = self.root / "results" / "auditorias"
        self.request_id, self.track_id, self.device_id = map(str, (uuid4(), uuid4(), uuid4()))
        self.input_hash = "a" * 64
        self.track = self.audit / self.track_id
        self.track.mkdir(parents=True)
        self.body = b'{"transcript":"fixture diagnostics", "evidences":[{"type":"THREAT","score":0.91}]}'
        compressed = gzip.compress(self.body)
        relative = self.request_id + "/ml-001.json.gz"
        raw = self.track / relative
        raw.parent.mkdir()
        raw.write_bytes(compressed)
        self.record = {
            "requestId": self.request_id, "trackId": self.track_id,
            "deviceId": self.device_id, "evidenceType": "AUDIO", "sha256": self.input_hash,
            "httpStatus": 201, "analysisStatus": "COMPLETE", "auditStatus": "RECORDED",
            "validForQuantitativeValidation": True, "expectedSteps": ["ConversationAnalysisStep"],
            "steps": [{"requestId": self.request_id, "step": "ConversationAnalysisStep",
                       "quality": "VALID", "signals": ["THREAT"]}],
            "consolidation": {"trackId": self.track_id, "accumulatedSignals": ["THREAT"],
                              "severity": "PELIGROSO", "verdict": "DANGER"},
            "mlResponses": [{"requestId": self.request_id, "step": "ConversationAnalysisStep",
                "endpoint": "/api/ml-service/v1/analysis/conversation", "httpStatus": 200,
                "path": relative, "bodyRepresentation": "ORIGINAL_BYTES",
                "storedSha256": hashlib.sha256(compressed).hexdigest(),
                "storedByteLength": len(compressed),
                "originalSha256": hashlib.sha256(self.body).hexdigest(),
                "originalByteLength": len(self.body)}],
        }

    def collect(self):
        self.principal = self.track / (self.request_id + ".json")
        self.principal.write_text(json.dumps(self.record), encoding="utf-8")
        return results.collect_audit(self.audit, self.destination, self.request_id,
                                     self.track_id, self.device_id, "AUDIO", self.input_hash)

    def test_copies_verified_originals_exactly_and_survives_source_deletion(self):
        collected = self.collect()
        self.assertTrue(collected["valid_for_quantitative_validation"])
        self.assertEqual(collected["integrity_errors"], [])
        self.assertEqual(len(collected["artifacts"]), 2)
        self.assertEqual((self.destination / collected["path"]).read_bytes(), self.principal.read_bytes())
        raw_path = self.record["mlResponses"][0]["path"]
        self.assertEqual((self.destination / raw_path).read_bytes(), (self.track / raw_path).read_bytes())
        self.principal.unlink()
        (self.track / raw_path).unlink()
        self.assertEqual(gzip.decompress((self.destination / raw_path).read_bytes()), self.body)

    def test_wrong_identity_never_copies_other_request(self):
        for field in ("requestId", "trackId", "deviceId", "evidenceType", "sha256"):
            with self.subTest(field=field):
                original = self.record[field]
                self.record[field] = "wrong"
                collected = self.collect()
                self.assertFalse(collected["valid_for_quantitative_validation"])
                self.assertTrue(collected["integrity_errors"])
                self.assertIsNone(collected["path"])
                self.record[field] = original

    def test_missing_main_is_reported_without_exception(self):
        collected = results.collect_audit(self.audit, self.destination, self.request_id,
            self.track_id, self.device_id, "AUDIO", self.input_hash)
        self.assertIsNone(collected["record"])
        self.assertTrue(collected["integrity_errors"])

    def test_compressed_and_original_hashes_are_checked(self):
        exchange = self.record["mlResponses"][0]
        for field in ("storedSha256", "originalSha256"):
            with self.subTest(field=field):
                original = exchange[field]
                exchange[field] = "0" * 64
                collected = self.collect()
                self.assertFalse(collected["valid_for_quantitative_validation"])
                self.assertTrue(collected["integrity_errors"])
                exchange[field] = original

    def test_sanitized_embedding_metadata_does_not_require_original_hash_match(self):
        exchange = self.record["mlResponses"][0]
        exchange["bodyRepresentation"] = "SANITIZED_JSON"
        exchange["originalSha256"] = "0" * 64
        collected = self.collect()
        self.assertTrue(collected["valid_for_quantitative_validation"])

    def test_corrupt_or_missing_gzip_keeps_incomplete_principal(self):
        raw_path = self.track / self.record["mlResponses"][0]["path"]
        raw_path.unlink()
        collected = self.collect()
        self.assertFalse(collected["valid_for_quantitative_validation"])
        self.assertTrue(collected["integrity_errors"])
        self.assertTrue((self.destination / collected["path"]).exists())

    def test_traversal_and_cross_request_references_are_rejected(self):
        exchange = self.record["mlResponses"][0]
        for index, path in enumerate(("../../secret.json.gz", "/tmp/secret.json.gz", "other/ml-001.json.gz",
                     self.request_id + "/../ml-001.json.gz", self.request_id + "\\ml-001.json.gz")):
            with self.subTest(path=path):
                self.destination = self.root / "results" / str(index)
                exchange["path"] = path
                collected = self.collect()
                self.assertFalse(collected["valid_for_quantitative_validation"])
                self.assertTrue(collected["integrity_errors"])
                self.assertEqual(len(collected["artifacts"]), 1)

    def test_symlink_outside_track_is_not_read_or_copied(self):
        raw_path = self.track / self.record["mlResponses"][0]["path"]
        outside = self.root / "outside.json.gz"
        outside.write_bytes(raw_path.read_bytes())
        raw_path.unlink()
        raw_path.symlink_to(outside)
        collected = self.collect()
        self.assertTrue(collected["integrity_errors"])
        self.assertFalse((self.destination / self.record["mlResponses"][0]["path"]).exists())

    def test_failed_analysis_is_preserved_without_claiming_integrity_failure(self):
        self.record["analysisStatus"] = "FAILED"
        self.record["validForQuantitativeValidation"] = False
        self.record["steps"][0].update(quality="FAILED", signals=[])
        self.record["mlResponses"] = []
        collected = self.collect()
        self.assertEqual(collected["integrity_errors"], [])
        self.assertEqual(collected["analysis_status"], "FAILED")
        self.assertFalse(collected["valid_for_quantitative_validation"])
        self.assertTrue((self.destination / collected["path"]).exists())

    def test_existing_different_destination_is_preserved(self):
        self.destination.mkdir(parents=True)
        target = self.destination / (self.request_id + ".json")
        target.write_text("existing evidence")
        collected = self.collect()
        self.assertTrue(collected["integrity_errors"])
        self.assertEqual(target.read_text(), "existing evidence")

    def test_complete_record_with_wrong_media_steps_is_invalid(self):
        self.record["expectedSteps"] = ["VideoAnalysisStep"]
        collected = self.collect()
        self.assertFalse(collected["valid_for_quantitative_validation"])
        self.assertTrue(any("tipo de evidencia" in error for error in collected["integrity_errors"]))

    def test_corrupt_compressed_stream_is_recorded_as_incomplete(self):
        exchange = self.record["mlResponses"][0]
        damaged = b"not a gzip stream"
        (self.track / exchange["path"]).write_bytes(damaged)
        exchange["storedSha256"] = hashlib.sha256(damaged).hexdigest()
        exchange["storedByteLength"] = len(damaged)
        collected = self.collect()
        self.assertFalse(collected["valid_for_quantitative_validation"])
        self.assertTrue(collected["integrity_errors"])

    def test_unknown_body_representation_invalidates_collection(self):
        self.record["mlResponses"][0]["bodyRepresentation"] = "UNKNOWN"
        collected = self.collect()
        self.assertFalse(collected["valid_for_quantitative_validation"])
        self.assertTrue(any("Representación" in error for error in collected["integrity_errors"]))

    def test_complete_record_cannot_hide_failed_ml_exchange(self):
        self.record["mlResponses"][0]["httpStatus"] = 500
        collected = self.collect()
        self.assertFalse(collected["valid_for_quantitative_validation"])
        self.assertTrue(any("fallido" in error for error in collected["integrity_errors"]))


def expectation(signal, condition="VALID_ANALYSIS", scope="evidence"):
    return {"signal": signal, "condition": condition, "scope": scope}


def specification(identifier, kind, *expected):
    return {"id": identifier, "file": identifier + ".bin", "media_type": kind,
            "expected_signals": list(expected), "unjustified_signals": [],
            "expected_visual_categories": []}


def evidence(spec, signals, accumulated=None, severity="NORMAL", quality="VALID", raw=None):
    complete = quality == "VALID"
    record = {"requestId": str(uuid4()), "steps": [{"step": "fixture", "signals": signals,
        "quality": quality}], "consolidation": {"accumulatedSignals": accumulated or signals,
        "severity": severity, "verdict": "DANGER" if severity == "PELIGROSO" else "CONTINUE",
        "message": "fixture"}}
    return {"id": spec["id"], "media_type": spec["media_type"], "httpStatus": 201,
            "audit": {"record": record, "raw_results": raw or [], "integrity_errors": [],
                      "valid_for_quantitative_validation": complete}}


class CaseAnalysisTest(unittest.TestCase):
    def case(self, specs, severity="NORMAL"):
        return {"id": "fixture", "expected_final_severity": severity, "evidence": specs,
                "unjustified_signals": []}

    def test_sequential_accumulation_preserves_previous_signals_and_evolution(self):
        image = specification("i", "IMAGE", expectation("UNKNOWN_PERSON"))
        audio = specification("a", "AUDIO", expectation("THREAT"))
        observed = [evidence(image, ["UNKNOWN_PERSON"], severity="LLAMATIVO"),
                    evidence(audio, ["THREAT"], ["UNKNOWN_PERSON", "THREAT"], "PELIGROSO")]
        result = results.analyze_case(self.case([image, audio], "PELIGROSO"), observed)
        self.assertEqual(result["comparison_status"], "MATCH")
        self.assertEqual(result["evidence"][1]["signals"], ["THREAT"])
        self.assertEqual([x["severity"] for x in result["severity_evolution"]], ["LLAMATIVO", "PELIGROSO"])

    def test_additional_signal_is_not_automatically_a_false_positive(self):
        image = specification("i", "IMAGE", expectation("KNOWN_PERSON"))
        result = results.analyze_case(self.case([image]), [evidence(image, ["KNOWN_PERSON", "PACKAGE_DETECTED"])])
        self.assertEqual(result["comparison_status"], "MATCH")
        self.assertEqual(result["evidence"][0]["additional_signals"], ["PACKAGE_DETECTED"])
        self.assertEqual(result["unjustified_signal_comparisons"], [])

    def test_only_explicit_unjustified_signals_can_fail_comparison(self):
        image = specification("i", "IMAGE")
        image["unjustified_signals"] = ["WEAPON_DETECTED"]
        result = results.analyze_case(self.case([image]), [evidence(image, ["WEAPON_DETECTED"])])
        self.assertEqual(result["comparison_status"], "MISMATCH")
        self.assertEqual(result["unjustified_signal_comparisons"][0]["status"], "UNJUSTIFIED_PRESENT")

    def test_insufficient_absence_is_not_reported_as_missing(self):
        audio = specification("a", "AUDIO", expectation("NO_SUSPICIOUS_CONVERSATION"))
        result = results.analyze_case(self.case([audio]), [evidence(audio, [], quality="INSUFFICIENT")])
        self.assertEqual(result["signal_comparisons"][0]["status"], "NOT_EVALUABLE")
        self.assertEqual(result["final_severity_comparison"], "NOT_EVALUABLE")
        self.assertEqual(result["comparison_status"], "INCOMPLETE")

    def test_movement_absence_keeps_condition_unestablished(self):
        video = specification("v", "VIDEO", expectation("LOITERING", "SUFFICIENT_MOTION", "modality"))
        result = results.analyze_case(self.case([video]), [evidence(video, ["NO_SUSPICIOUS_BEHAVIOR"])])
        self.assertEqual(result["signal_comparisons"][0]["status"], "CONDITION_NOT_ESTABLISHED")
        self.assertEqual(result["comparison_status"], "REVIEW_REQUIRED")

    def test_manual_covering_does_not_become_an_automatic_omission(self):
        image = specification("i2", "IMAGE", expectation("COVERED_FACE", "MANUAL_REVIEW"))
        result = results.analyze_case(self.case([image]), [evidence(image, [])])
        self.assertEqual(result["signal_comparisons"][0]["status"], "REVIEW_REQUIRED")

    def test_prolonged_presence_is_found_in_new_consolidation_signals(self):
        first = specification("v1", "VIDEO", expectation("PROLONGED_VISIT_PRESENCE", "SUFFICIENT_MOTION", "modality"))
        second = deepcopy(first)
        second["id"] = "v2"
        observed = [evidence(first, ["NO_SUSPICIOUS_BEHAVIOR"]),
                    evidence(second, ["NO_SUSPICIOUS_BEHAVIOR"], ["NO_SUSPICIOUS_BEHAVIOR", "PROLONGED_VISIT_PRESENCE"])]
        result = results.analyze_case(self.case([first, second]), observed)
        self.assertTrue(all(item["status"] == "PRESENT" for item in result["signal_comparisons"]))
        self.assertIn("PROLONGED_VISIT_PRESENCE", result["evidence"][1]["consolidation_added_signals"])

    def test_firearm_and_melee_categories_are_checked_in_raw_labels(self):
        for category in ("Firearm", "Melee_Weapon"):
            with self.subTest(category=category):
                image = specification("i", "IMAGE", expectation("WEAPON_DETECTED"))
                image["expected_visual_categories"] = [category]
                raw = [{"endpoint": "/analysis/object-detection", "body": {"data": {"objects": [{"label": category}]}}}]
                result = results.analyze_case(self.case([image]), [evidence(image, ["WEAPON_DETECTED"], raw=raw)])
                self.assertEqual(result["visual_category_comparisons"][0]["status"], "PRESENT")

    def test_incomplete_case_preserves_last_observed_severity_without_matching(self):
        image, audio = specification("i", "IMAGE"), specification("a", "AUDIO")
        result = results.analyze_case(self.case([image, audio]), [evidence(image, [], severity="LLAMATIVO")])
        self.assertEqual(result["comparison_status"], "INCOMPLETE")
        self.assertEqual(result["observed_final_severity"], "LLAMATIVO")
        self.assertEqual(result["final_severity_comparison"], "NOT_EVALUABLE")

    def test_transcript_is_diagnostic_without_literal_or_wer_comparison(self):
        audio = specification("a", "AUDIO", expectation("THREAT"))
        raw = [{"endpoint": "/analysis/conversation", "body": {"transcript": "diagnostic fixture", "evidences": []}}]
        result = results.analyze_case(self.case([audio]), [evidence(audio, ["THREAT"], raw=raw)])
        self.assertEqual(result["evidence"][0]["transcriptions"], ["diagnostic fixture"])
        self.assertNotIn("wer", json.dumps(result).lower())

    def test_corrupt_incomplete_observations_do_not_raise_or_look_semantically_valid(self):
        image = specification("i", "IMAGE", expectation("KNOWN_PERSON"))
        observed = evidence(image, [])
        observed["audit"].update(integrity_errors=["corrupt audit"], valid_for_quantitative_validation=False)
        observed["audit"]["record"].update(steps=[{"signals": None}], consolidation="corrupt")
        result = results.analyze_case(self.case([image]), [observed])
        self.assertEqual(result["comparison_status"], "INCOMPLETE")
        self.assertEqual(result["signal_comparisons"][0]["status"], "NOT_EVALUABLE")

    def test_transport_error_or_uncertainty_cannot_be_valid_even_with_local_audit(self):
        image = specification("i", "IMAGE", expectation("KNOWN_PERSON"))
        for override in ({"error": "read failed after HTTP201"},
                         {"httpOutcome": "UNCERTAIN"}, {"httpOutcome": "NOT_SENT"}):
            with self.subTest(override=override):
                observed = evidence(image, ["KNOWN_PERSON"])
                observed.update(override)
                result = results.analyze_case(self.case([image]), [observed])
                self.assertFalse(result["processing_complete"])
                self.assertEqual(result["comparison_status"], "INCOMPLETE")
                self.assertEqual(result["signal_comparisons"][0]["status"], "NOT_EVALUABLE")

    def test_gap_does_not_attribute_old_image_signals_to_the_next_audio(self):
        image = specification("i", "IMAGE", expectation("KNOWN_PERSON"))
        audio = specification("a", "AUDIO", expectation("NO_SUSPICIOUS_CONVERSATION"))
        video = specification("v", "VIDEO")
        missing = {"id": "i", "httpStatus": None, "error": "timeout", "audit": {}}
        observed_audio = evidence(audio, ["NO_SUSPICIOUS_CONVERSATION"],
            ["KNOWN_PERSON", "NO_SUSPICIOUS_CONVERSATION"])
        observed_video = evidence(video, ["NO_SUSPICIOUS_BEHAVIOR"],
            ["KNOWN_PERSON", "NO_SUSPICIOUS_CONVERSATION", "NO_SUSPICIOUS_BEHAVIOR", "PROLONGED_VISIT_PRESENCE"])
        result = results.analyze_case(self.case([image, audio, video]),
                                     [missing, observed_audio, observed_video])
        audio_observation, video_observation = result["evidence"][1:]
        self.assertEqual(audio_observation["consolidation_signal_attribution"], "UNKNOWN_PREVIOUS_SNAPSHOT")
        self.assertEqual(audio_observation["consolidation_added_signals"], [])
        self.assertNotIn("KNOWN_PERSON", audio_observation["signals"])
        self.assertIn("KNOWN_PERSON", audio_observation["accumulated_signals"])
        self.assertEqual(video_observation["consolidation_signal_attribution"], "KNOWN_PREVIOUS_SNAPSHOT")
        self.assertEqual(video_observation["consolidation_added_signals"], ["PROLONGED_VISIT_PRESENCE"])


if __name__ == "__main__":
    unittest.main()
