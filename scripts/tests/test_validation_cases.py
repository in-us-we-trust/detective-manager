"""Pruebas del catálogo aprobado e inventario sin abrir evidencias ni usar inferencias."""

from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "validation_cases.py"
SPEC = importlib.util.spec_from_file_location("validation_cases", SCRIPT)
catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(catalog)


class ValidationCaseCatalogTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def file(self, case_id, name, content=b"synthetic metadata fixture"):
        category, number = case_id.split(".")
        path = self.root / category / number / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        return path

    def cases(self):
        return {case["id"]: case for case in catalog.build_cases(self.root)["cases"]}

    def test_approved_catalog_has_exactly_40_cases_and_explicit_category_counts(self):
        cases = self.cases()
        expected_ids = {
            f"{category}.{number}"
            for category, count in {1: 7, 2: 8, 3: 7, 4: 7, 5: 8, 6: 4}.items()
            for number in range(1, count + 1)
        }
        expected_ids.remove("3.4")
        self.assertEqual(set(cases), expected_ids)
        self.assertEqual(len(cases), 40)
        self.assertEqual(catalog.COUNTS, {1: 7, 2: 8, 3: 6, 4: 7, 5: 8, 6: 4})
        self.assertTrue(all(case["configuration_status"] == "PENDING" for case in cases.values()))
        for case in cases.values():
            self.assertEqual(case["folder"], case["id"].replace(".", "/"))
            self.assertNotIn("deviceId", case)
            self.assertNotIn("uuid", case)
            self.assertEqual(case["unjustified_signals"], [])

    def test_device_aliases_person_and_known_status_follow_the_approved_mapping(self):
        cases = self.cases()
        for case_id, case in cases.items():
            category = int(case_id.split(".")[0])
            expected_person = "juli" if category <= 3 else "mate"
            expected_device = "juli" if category == 1 or category >= 4 else "mate"
            if case_id in ("4.2", "5.3", "6.4"):
                expected_device = "mate"
            self.assertEqual(case["person"], expected_person, case_id)
            self.assertEqual(case["device"], expected_device, case_id)
            self.assertEqual(case["expected_known"], expected_person == expected_device, case_id)

    def test_final_severities_match_every_approved_case(self):
        expected = {}
        expected.update({f"1.{n}": "NORMAL" for n in range(1, 8)})
        expected.update({f"2.{n}": "LLAMATIVO" for n in range(1, 9)})
        expected.update({f"3.{n}": "SOSPECHOSO" if n <= 4 else "LLAMATIVO" for n in range(1, 8)})
        expected.update(zip(
            (f"4.{n}" for n in range(1, 8)),
            ("LLAMATIVO", "NORMAL", "SOSPECHOSO", "LLAMATIVO", "LLAMATIVO", "SOSPECHOSO", "LLAMATIVO"),
        ))
        expected.update({f"5.{n}": "PELIGROSO" if n <= 6 else "LLAMATIVO" for n in range(1, 9)})
        expected.update(zip(
            (f"6.{n}" for n in range(1, 5)),
            ("SOSPECHOSO", "SOSPECHOSO", "LLAMATIVO", "NORMAL"),
        ))
        expected.pop("3.4")
        actual = {case_id: case["expected_final_severity"] for case_id, case in self.cases().items()}
        self.assertEqual(actual, expected)

    def test_catalog_keeps_nonstandard_approved_sequences_and_audio_only_cases(self):
        cases = self.cases()
        self.assertEqual([x["media_type"] for x in cases["5.6"]["evidence"]], ["VIDEO", "IMAGE", "AUDIO"])
        self.assertEqual([x["media_type"] for x in cases["6.4"]["evidence"]], ["IMAGE", "IMAGE", "AUDIO"])
        for case_id in ("3.2", "5.2"):
            self.assertEqual([x["media_type"] for x in cases[case_id]["evidence"]], ["AUDIO"])
        for case_id in ("1.7", "2.8", "4.5", "4.6", "4.7", "6.2"):
            self.assertEqual([x["media_type"] for x in cases[case_id]["evidence"]], ["IMAGE", "VIDEO", "AUDIO"])

    def test_excluded_case_is_not_reintroduced_or_renumbered(self):
        cases = self.cases()
        self.assertNotIn("3.4", cases)
        self.assertEqual(cases["3.5"]["folder"], "3/5")
        self.assertEqual(cases["3.7"]["expected_final_severity"], "LLAMATIVO")
        self.assertEqual(sum(case["category"] == 3 for case in cases.values()), 6)

    def test_expected_signals_keep_manual_conditions_and_approved_visual_categories(self):
        cases = self.cases()
        manual_62 = cases["6.2"]["evidence"][0]["expected_signals"]
        self.assertEqual(manual_62, [{"signal": "COVERED_FACE", "condition": "MANUAL_REVIEW", "scope": "evidence"}])
        manual_64 = cases["6.4"]["evidence"][1]["expected_signals"]
        self.assertEqual(manual_64[0]["condition"], "MANUAL_REVIEW")
        self.assertTrue(cases["6.4"]["evidence"][1]["review_notes"])
        self.assertEqual(cases["5.4"]["evidence"][0]["expected_visual_categories"], ["Firearm"])
        self.assertEqual(cases["5.5"]["evidence"][0]["expected_visual_categories"], ["Melee_Weapon"])
        for case in cases.values():
            for evidence in case["evidence"]:
                for expectation in evidence["expected_signals"]:
                    if evidence["media_type"] == "VIDEO":
                        self.assertEqual(expectation["scope"], "modality")
                    if expectation["signal"].startswith("NO_"):
                        self.assertEqual(expectation["condition"], "VALID_ANALYSIS")
        movement = cases["4.3"]["evidence"][1]["expected_signals"]
        self.assertTrue(all(item["condition"] == "SUFFICIENT_MOTION" for item in movement))

    def test_inventory_association_only_uses_names_extensions_and_stat(self):
        path = self.file("1.5", "reference.JPG")
        audio = self.file("1.5", "speech.MP3")
        before = {p: (p.stat().st_mtime_ns, p.stat().st_size) for p in (path, audio)}
        with patch.object(Path, "open", side_effect=AssertionError("No evidence content reads")), \
                patch.object(Path, "read_bytes", side_effect=AssertionError("No evidence content reads")), \
                patch.object(Path, "read_text", side_effect=AssertionError("No evidence content reads")):
            case = self.cases()["1.5"]
        self.assertEqual(case["configuration_status"], "READY")
        self.assertEqual([e["file"] for e in case["evidence"]], ["reference.JPG", "speech.MP3"])
        self.assertEqual({p: (p.stat().st_mtime_ns, p.stat().st_size) for p in (path, audio)}, before)
        self.assertTrue(all(item["size_bytes"] > 0 for item in case["inventory"]))

    def test_equal_filenames_in_different_folders_never_reuse_a_route(self):
        first = self.file("1.1", "reference.jpg")
        second = self.file("2.1", "reference.jpg")
        cases = self.cases()
        self.assertNotEqual(
            self.root / cases["1.1"]["folder"] / cases["1.1"]["evidence"][0]["file"],
            self.root / cases["2.1"]["folder"] / cases["2.1"]["evidence"][0]["file"],
        )
        self.assertEqual(cases["1.1"]["evidence"][0]["file"], first.name)
        self.assertEqual(cases["2.1"]["evidence"][0]["file"], second.name)
        self.assertIsNone(cases["1.2"]["evidence"][0]["file"])
        self.assertEqual(cases["1.2"]["configuration_status"], "PENDING")

    def test_multiple_unnumbered_images_remain_pending_without_alphabetical_guessing(self):
        self.file("5.4", "front.jpg")
        self.file("5.4", "side.jpg")
        case = self.cases()["5.4"]
        self.assertEqual(case["configuration_status"], "PENDING")
        self.assertIsNone(case["evidence"][0]["file"])
        self.assertTrue(any("ambiguo" in issue for issue in case["configuration_issues"]))

    def test_numeric_order_expands_repeated_type_at_its_original_position(self):
        self.file("5.6", "evidence_10.jpg")
        self.file("5.6", "evidence_2.jpg")
        self.file("5.6", "motion.mp4")
        self.file("5.6", "speech.wav")
        case = self.cases()["5.6"]
        self.assertEqual(case["configuration_status"], "READY")
        self.assertEqual([e["media_type"] for e in case["evidence"]], ["VIDEO", "IMAGE", "IMAGE", "AUDIO"])
        self.assertEqual([e["file"] for e in case["evidence"]], ["motion.mp4", "evidence_2.jpg", "evidence_10.jpg", "speech.wav"])
        self.assertEqual([e["id"] for e in case["evidence"]], ["video-1", "image-1", "image-2", "audio-1"])

    def test_video_chunks_keep_original_names_and_numeric_order(self):
        self.file("1.6", "reference.jpg")
        self.file("1.6", "chunk_010.mp4")
        self.file("1.6", "chunk_002.mp4")
        case = self.cases()["1.6"]
        self.assertEqual([e["file"] for e in case["evidence"]], ["reference.jpg", "chunk_002.mp4", "chunk_010.mp4"])
        self.assertTrue(all(e["expected_signals"][0]["scope"] == "modality" for e in case["evidence"][1:]))

    def test_64_maps_two_numbered_images_to_distinct_approved_slots(self):
        self.file("6.4", "image_2.jpg")
        self.file("6.4", "image_1.jpg")
        self.file("6.4", "speech.mp3")
        case = self.cases()["6.4"]
        self.assertEqual(case["configuration_status"], "READY")
        self.assertEqual([e["file"] for e in case["evidence"]], ["image_1.jpg", "image_2.jpg", "speech.mp3"])
        first, second = case["evidence"][:2]
        self.assertEqual([e["signal"] for e in first["expected_signals"]], ["KNOWN_PERSON", "NO_THREAT_OBJECT"])
        self.assertEqual(second["expected_signals"][0]["condition"], "MANUAL_REVIEW")

    def test_64_missing_second_image_does_not_assign_the_only_image_to_a_guess(self):
        self.file("6.4", "covered.jpg")
        self.file("6.4", "speech.mp3")
        case = self.cases()["6.4"]
        self.assertEqual(case["configuration_status"], "PENDING")
        self.assertEqual([e["file"] for e in case["evidence"]], [None, None, "speech.mp3"])
        self.assertEqual(case["inventory"][0]["file"], "covered.jpg")

    def test_extra_modalities_and_empty_files_are_reported_pending(self):
        self.file("1.1", "reference.jpg", b"")
        self.file("1.1", "unexpected.wav")
        case = self.cases()["1.1"]
        self.assertEqual(case["configuration_status"], "PENDING")
        self.assertTrue(any("vacío" in issue for issue in case["configuration_issues"]))
        self.assertTrue(any("no prevé" in issue for issue in case["configuration_issues"]))

    def test_symlinks_cannot_borrow_evidence_from_another_case(self):
        source = self.file("1.1", "reference.jpg")
        second = self.root / "2" / "1"
        second.parent.mkdir(parents=True)
        second.symlink_to(source.parent, target_is_directory=True)
        case = self.cases()["2.1"]
        self.assertEqual(case["configuration_status"], "PENDING")
        self.assertIsNone(case["evidence"][0]["file"])
        self.assertTrue(any("enlace" in issue for issue in case["configuration_issues"]))

    def test_cli_preserves_existing_private_configuration_without_force(self):
        self.file("1.1", "reference.jpg")
        output = self.root / "cases.json"
        with redirect_stdout(io.StringIO()):
            self.assertEqual(catalog.main(["--root", str(self.root)]), 0)
        initial = json.loads(output.read_text())
        initial["manual_note"] = "Preserve explicit associations"
        output.write_text(json.dumps(initial))
        original = output.read_bytes()
        with redirect_stderr(io.StringIO()):
            self.assertEqual(catalog.main(["--root", str(self.root)]), 1)
        self.assertEqual(output.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
