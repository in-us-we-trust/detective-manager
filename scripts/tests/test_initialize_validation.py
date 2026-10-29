"""Prueba configuración e integridad del seed sin imágenes ni reconocimiento simulado.

Los bytes temporales son archivos sintéticos para validar lecturas y hashes; nunca
se envían a ML ni representan imágenes de personas. Los clientes de lectura sólo
contienen DTOs estructurales y rechazan cualquier escritura.
"""

import base64
from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError


SCRIPT = Path(__file__).resolve().parents[1] / "initialize-validation.py"
SPEC = importlib.util.spec_from_file_location("initialize_validation", SCRIPT)
seed = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = seed
SPEC.loader.exec_module(seed)

DEVICE_A = "11111111-1111-4111-8111-111111111111"
DEVICE_B = "22222222-2222-4222-8222-222222222222"
PERSON_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
PERSON_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
API = "/api/detective-manager/v1"


class ReadOnlyManager:
    """Datos de API para controles de integridad; no contiene API de ML."""

    def __init__(self, records, downloads=None):
        self.records = records
        self.downloads = downloads or {}
        self.calls = []

    def manager(self, method, path, payload=None, allow_missing=False):
        if method != "GET" or payload is not None:
            raise AssertionError("Los controles de integridad no pueden escribir registros")
        if path.startswith(API):
            path = path[len(API):]
        self.calls.append(path)
        if path not in self.records:
            raise AssertionError("Lectura no esperada: " + path)
        response = self.records[path]
        if response is None and not allow_missing:
            raise AssertionError("No se habilitó allow_missing para el dispositivo ausente")
        return deepcopy(response)

    def download(self, path):
        if path not in self.downloads:
            raise AssertionError("Descarga no esperada: " + path)
        return self.downloads[path]


class InitializationFixtures(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        references = self.directory / "references"
        references.mkdir()
        self.file_a = references / "reference-a.jpg"
        self.file_b = references / "reference-b.jpg"
        self.file_a.write_bytes(b"synthetic-reference-file-A")
        self.file_b.write_bytes(b"synthetic-reference-file-B")
        self.path = self.directory / "seed.local.json"
        self.raw = {
            "version": 1,
            "manager_base_url": "http://localhost:8088",
            "ml_base_url": "http://localhost:8000",
            "timeout_seconds": 15,
            "devices": [
                {
                    "id": DEVICE_A,
                    "label": "Synthetic device A",
                    "person_name": "Synthetic identity A",
                    "images": ["references/reference-a.jpg"],
                },
                {
                    "id": DEVICE_B,
                    "label": "Synthetic device B",
                    "person_name": "Synthetic identity B",
                    "images": ["references/reference-b.jpg"],
                },
            ],
        }

    def save_config(self, raw=None):
        self.path.write_text(json.dumps(self.raw if raw is None else raw), encoding="utf-8")
        return self.path

    def config(self):
        return seed.load_config(self.save_config())

    def records_for(self, device, person_id=PERSON_A, image_id=17):
        image_url = "/validation/media/" + device.id + "/reference.bin"
        person = {
            "id": person_id,
            "deviceId": device.id,
            "name": device.person_name,
            "status": "KNOWN",
            "role": None,
            "images": [{"id": image_id, "imageUrl": image_url}],
        }
        embedding = {
            "id": image_id,
            "deviceId": device.id,
            "personId": person_id,
            "personName": device.person_name,
            "embedding": [0.125, -0.25, 0.5],
            "modelName": "SyntheticModel",
            "detectorBackend": "synthetic-detector",
            "dimensions": 3,
            "createdAt": "2026-01-01T00:00:00",
        }
        device_record = {
            "id": device.id,
            "serialNumber": device.label,
            "status": "UNCLAIMED",
            "detectionThreshold": 90,
        }
        prefix = "/devices/" + device.id
        records = {
            prefix: device_record,
            prefix + "/persons": [person],
            prefix + "/persons/" + person_id + "/face-embeddings": [embedding],
        }
        return records, {image_url: device.images[0].payload}, person, embedding


class InitializationConfigTest(InitializationFixtures):
    def test_reference_paths_are_relative_to_config_file(self):
        config = self.config()
        self.assertEqual(config.manager_url, "http://localhost:8088")
        self.assertEqual(config.ml_url, "http://localhost:8000")
        self.assertEqual(config.devices[0].images[0].path, self.file_a.resolve())
        self.assertEqual(config.devices[0].images[0].payload, self.file_a.read_bytes())
        self.assertEqual(len(config.devices[0].images[0].sha256), 64)

    def test_requires_exactly_two_distinct_device_ids(self):
        changes = [
            [],
            self.raw["devices"][:1],
            self.raw["devices"] + [deepcopy(self.raw["devices"][0])],
        ]
        duplicate = deepcopy(self.raw["devices"])
        duplicate[1]["id"] = duplicate[0]["id"]
        changes.append(duplicate)
        for devices in changes:
            with self.subTest(devices=devices):
                raw = deepcopy(self.raw)
                raw["devices"] = devices
                with self.assertRaises(seed.InitializationError):
                    seed.load_config(self.save_config(raw))

    def test_rejects_unknown_config_fields_or_versions(self):
        for key, value in (("version", 2), ("version", True), ("unexpected", "value")):
            with self.subTest(key=key, value=value):
                raw = deepcopy(self.raw)
                raw[key] = value
                with self.assertRaises(seed.InitializationError):
                    seed.load_config(self.save_config(raw))

    def test_rejects_malformed_uuid(self):
        self.raw["devices"][0]["id"] = "not-a-uuid"
        with self.assertRaises(seed.InitializationError):
            self.config()

    def test_requires_distinct_nonempty_identity_names(self):
        for name in ("", "   ", " synthetic IDENTITY A "):
            with self.subTest(name=name):
                self.raw["devices"][1]["person_name"] = name
                with self.assertRaises(seed.InitializationError):
                    self.config()

    def test_missing_reference_fails_before_network_or_writes(self):
        self.file_b.unlink()
        with patch.object(seed, "urlopen") as network, redirect_stderr(io.StringIO()), \
                redirect_stdout(io.StringIO()):
            result = seed.main(["--config", str(self.save_config())])
        self.assertNotEqual(result, 0)
        network.assert_not_called()

    def test_missing_reference_error_identifies_the_file(self):
        self.file_b.unlink()
        with self.assertRaises(seed.InitializationError) as error:
            self.config()
        self.assertIn(self.file_b.name, str(error.exception))

    def test_rejects_empty_file(self):
        self.file_b.write_bytes(b"")
        with self.assertRaises(seed.InitializationError):
            self.config()

    def test_rejects_duplicate_references_within_or_between_people(self):
        self.raw["devices"][0]["images"].append("references/reference-a.jpg")
        with self.assertRaises(seed.InitializationError):
            self.config()
        self.raw["devices"][0]["images"].pop()
        self.file_b.write_bytes(self.file_a.read_bytes())
        with self.assertRaises(seed.InitializationError):
            self.config()

    def test_requires_at_least_one_reference_per_person(self):
        self.raw["devices"][1]["images"] = []
        with self.assertRaises(seed.InitializationError):
            self.config()

    def test_rejects_invalid_urls_and_timeouts(self):
        for key, value in (
            ("manager_base_url", "localhost:8088"),
            ("ml_base_url", "file:///tmp/ml"),
            ("manager_base_url", "http://"),
            ("timeout_seconds", 0),
            ("timeout_seconds", -1),
            ("timeout_seconds", "tomorrow"),
            ("timeout_seconds", float("inf")),
            ("timeout_seconds", True),
        ):
            with self.subTest(key=key, value=value):
                raw = deepcopy(self.raw)
                raw[key] = value
                with self.assertRaises(seed.InitializationError):
                    seed.load_config(self.save_config(raw))

    def test_check_config_is_read_only_and_does_not_call_services(self):
        with patch.object(seed, "urlopen") as network, redirect_stdout(io.StringIO()), \
                redirect_stderr(io.StringIO()):
            result = seed.main(["--config", str(self.save_config()), "--check-config"])
        self.assertEqual(result, 0)
        network.assert_not_called()


class InitializationIntegrityTest(InitializationFixtures):
    def test_structurally_valid_embedding_is_accepted_without_ml(self):
        device = self.config().devices[0]
        _, _, person, embedding = self.records_for(device)
        seed.validate_embedding(embedding, device, person)

    def test_rejects_wrong_embedding_ownership_and_missing_metadata(self):
        device = self.config().devices[0]
        _, _, person, base = self.records_for(device)
        for key, value in (
            ("deviceId", DEVICE_B),
            ("personId", PERSON_B),
            ("personName", "Another synthetic identity"),
            ("modelName", ""),
            ("detectorBackend", ""),
            ("id", None),
            ("createdAt", "invalid-date"),
        ):
            with self.subTest(key=key):
                embedding = deepcopy(base)
                embedding[key] = value
                with self.assertRaises(seed.InitializationError):
                    seed.validate_embedding(embedding, device, person)

    def test_rejects_invalid_vector_and_dimension_mismatch(self):
        device = self.config().devices[0]
        _, _, person, base = self.records_for(device)
        for vector in ([], None, "0.1,0.2", [True, 1.0, 2.0], [0.1, float("nan"), 0.2],
                       [0.1, float("inf"), 0.2], [0.1, "value", 0.2], [0.1]):
            with self.subTest(vector=vector):
                embedding = deepcopy(base)
                embedding["embedding"] = vector
                with self.assertRaises(seed.InitializationError):
                    seed.validate_embedding(embedding, device, person)

    def test_existing_complete_device_is_inspected_without_mutations(self):
        device = self.config().devices[0]
        records, downloads, person, _ = self.records_for(device)
        client = ReadOnlyManager(records, downloads)
        state = seed.inspect_device(client, device)
        self.assertEqual(state.device["id"], device.id)
        self.assertEqual(state.person["id"], person["id"])
        self.assertEqual(len(state.embeddings), 1)

    def test_absent_device_is_planned_without_creating_it(self):
        device = self.config().devices[0]
        prefix = "/devices/" + device.id
        client = ReadOnlyManager({prefix: None, prefix + "/persons": []})
        state = seed.inspect_device(client, device)
        self.assertIsNone(state.device)
        self.assertIsNone(state.person)
        self.assertEqual(state.embeddings, [])

    def test_rejects_duplicate_or_crossed_people(self):
        config = self.config()
        device = config.devices[0]
        for crossed in (False, True):
            with self.subTest(crossed=crossed):
                records, downloads, person, _ = self.records_for(device)
                if crossed:
                    person["name"] = config.devices[1].person_name
                    records["/devices/" + device.id + "/persons"] = [person]
                else:
                    duplicate = deepcopy(person)
                    duplicate["id"] = PERSON_B
                    records["/devices/" + device.id + "/persons"].append(duplicate)
                with self.assertRaises(seed.InitializationError):
                    seed.inspect_device(ReadOnlyManager(records, downloads), device)

    def test_rejects_person_with_wrong_device_or_not_known(self):
        device = self.config().devices[0]
        for key, value in (("deviceId", DEVICE_B), ("status", "UNKNOWN")):
            with self.subTest(key=key):
                records, downloads, person, _ = self.records_for(device)
                person[key] = value
                records["/devices/" + device.id + "/persons"] = [person]
                with self.assertRaises(seed.InitializationError):
                    seed.inspect_device(ReadOnlyManager(records, downloads), device)

    def test_rejects_device_identity_mismatch(self):
        device = self.config().devices[0]
        records, downloads, _, _ = self.records_for(device)
        records["/devices/" + device.id]["serialNumber"] = "Different device"
        with self.assertRaises(seed.InitializationError):
            seed.inspect_device(ReadOnlyManager(records, downloads), device)

    def test_rejects_missing_or_duplicated_embeddings(self):
        device = self.config().devices[0]
        for duplicated in (False, True):
            with self.subTest(duplicated=duplicated):
                records, downloads, _, embedding = self.records_for(device)
                records["/devices/" + device.id + "/persons/" + PERSON_A + "/face-embeddings"] = \
                    [embedding, deepcopy(embedding)] if duplicated else []
                with self.assertRaises(seed.InitializationError):
                    seed.inspect_device(ReadOnlyManager(records, downloads), device)

    def test_rejects_stored_image_that_does_not_match_reference(self):
        device = self.config().devices[0]
        records, downloads, _, _ = self.records_for(device)
        downloads[next(iter(downloads))] = b"different-synthetic-file"
        with self.assertRaises(seed.InitializationError):
            seed.inspect_device(ReadOnlyManager(records, downloads), device)

    def test_inconsistent_second_device_does_not_create_missing_first_device(self):
        config = self.config()
        first, second = config.devices
        first_path = "/devices/" + first.id
        records, downloads, _, _ = self.records_for(second, PERSON_B, 18)
        records[first_path] = None
        records[first_path + "/persons"] = []
        records["/devices/" + second.id]["serialNumber"] = "Conflicting device label"
        client = ReadOnlyManager(records, downloads)
        with self.assertRaises(seed.InitializationError):
            seed.build_plan(client, config)
        self.assertIn(first_path, client.calls)
        self.assertIn("/devices/" + second.id, client.calls)

    def test_build_plan_reads_both_devices_and_does_not_mutate_them(self):
        config = self.config()
        all_records = {}
        all_downloads = {}
        for device, person_id, image_id in zip(config.devices, (PERSON_A, PERSON_B), (17, 18)):
            records, downloads, _, _ = self.records_for(device, person_id, image_id)
            all_records.update(records)
            all_downloads.update(downloads)
        client = ReadOnlyManager(all_records, all_downloads)
        states = seed.build_plan(client, config)
        self.assertEqual(len(states), 2)
        self.assertEqual([state.device["id"] for state in states], [DEVICE_A, DEVICE_B])
        self.assertEqual(len(client.calls), 6)


class ManagerWriteRecorder:
    """Registra contratos de alta del Manager; no implementa ningún análisis ML."""

    def __init__(self, fail_on=None):
        self.calls = []
        self.fail_on = fail_on

    def manager(self, method, path, payload=None, allow_missing=False):
        if method != "POST" or not isinstance(payload, dict):
            raise AssertionError("Sólo se esperan contratos de alta POST del Manager")
        self.calls.append((path, deepcopy(payload)))
        if len(self.calls) == self.fail_on:
            raise seed.InitializationError("Fallo de transporte durante un alta")
        return {}


class InitializationApplyPlanTest(InitializationFixtures):
    def test_complete_states_do_not_duplicate_records(self):
        config = self.config()
        states = []
        for device, person_id, image_id in zip(config.devices, (PERSON_A, PERSON_B), (17, 18)):
            records, downloads, _, _ = self.records_for(device, person_id, image_id)
            states.append(seed.inspect_device(ReadOnlyManager(records, downloads), device))
        client = ManagerWriteRecorder()
        seed.apply_plan(client, states)
        self.assertEqual(client.calls, [])

    def test_absent_states_create_two_devices_and_two_people_with_original_files(self):
        config = self.config()
        states = [seed.DeviceState(device, None, None, []) for device in config.devices]
        client = ManagerWriteRecorder()
        seed.apply_plan(client, states)
        self.assertEqual(len(client.calls), 4)
        for index, device in enumerate(config.devices):
            device_path, device_payload = client.calls[2 * index]
            person_path, person_payload = client.calls[2 * index + 1]
            self.assertEqual(device_path, API + "/devices")
            self.assertEqual(set(device_payload), {"id", "serialNumber", "lastSeen", "lastBoot"})
            self.assertEqual(device_payload["id"], device.id)
            self.assertEqual(device_payload["serialNumber"], device.label)
            self.assertEqual(device_payload["lastBoot"], device_payload["lastSeen"])
            self.assertEqual(person_path, API + "/devices/" + device.id + "/persons")
            self.assertEqual(set(person_payload), {"name", "images"})
            self.assertEqual(person_payload["name"], device.person_name)
            self.assertEqual(
                [base64.b64decode(value, validate=True) for value in person_payload["images"]],
                [image.payload for image in device.images],
            )

    def test_existing_device_with_absent_person_only_creates_person(self):
        device = self.config().devices[0]
        state = seed.DeviceState(device, {"id": device.id}, None, [])
        client = ManagerWriteRecorder()
        seed.apply_plan(client, [state])
        self.assertEqual(len(client.calls), 1)
        self.assertEqual(client.calls[0][0], API + "/devices/" + device.id + "/persons")

    def test_failed_post_stops_without_retry_or_processing_following_devices(self):
        config = self.config()
        states = [seed.DeviceState(device, None, None, []) for device in config.devices]
        for fail_on in (1, 2):
            with self.subTest(fail_on=fail_on):
                client = ManagerWriteRecorder(fail_on=fail_on)
                with self.assertRaises(seed.InitializationError):
                    seed.apply_plan(client, states)
                self.assertEqual(len(client.calls), fail_on)
                self.assertTrue(all(DEVICE_B not in path for path, _ in client.calls))


class HttpResponse:
    """Respuesta de transporte genérica, sin resultados de inferencia."""

    def __init__(self, url, payload):
        self.url = url
        self.body = json.dumps(payload).encode("utf-8")

    def geturl(self):
        return self.url

    def read(self):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class InitializationTransportTest(InitializationFixtures):
    def manager_schema(self):
        return {"paths": {
            API + "/devices": {},
            API + "/devices/{deviceId}/persons": {},
            API + "/devices/{deviceId}/persons/{personId}/face-embeddings": {},
        }}

    def test_service_availability_only_uses_openapi_and_health_gets(self):
        config = self.config()
        expected = [
            (config.manager_url + "/v3/api-docs", self.manager_schema()),
            (config.ml_url + "/api/ml-service/v1/health", {"status": "ok"}),
        ]
        calls = []

        def transport(request, timeout):
            self.assertEqual(request.get_method(), "GET")
            self.assertIsNone(request.data)
            self.assertEqual(timeout, config.timeout)
            url, response = expected[len(calls)]
            self.assertEqual(request.full_url, url)
            calls.append(url)
            return HttpResponse(url, response)

        with patch.object(seed, "urlopen", side_effect=transport):
            seed.ApiClient(config).check_services()
        self.assertEqual(len(calls), 2)

    def test_invalid_manager_contract_does_not_contact_ml(self):
        config = self.config()
        for schema in ({"paths": {}}, {"paths": None}, None):
            with self.subTest(schema=schema), patch.object(
                seed, "urlopen", return_value=HttpResponse(config.manager_url + "/v3/api-docs", schema)
            ) as network:
                with self.assertRaises(seed.InitializationError):
                    seed.ApiClient(config).check_services()
                self.assertEqual(network.call_count, 1)

    def test_unready_ml_health_aborts_without_inference(self):
        config = self.config()
        with patch.object(seed, "urlopen", side_effect=[
            HttpResponse(config.manager_url + "/v3/api-docs", self.manager_schema()),
            HttpResponse(config.ml_url + "/api/ml-service/v1/health", {"status": "loading"}),
        ]) as network:
            with self.assertRaises(seed.InitializationError) as error:
                seed.ApiClient(config).check_services()
        self.assertIn("ML Service", str(error.exception))
        self.assertEqual(network.call_count, 2)

    def test_only_http_404_is_interpreted_as_missing_record(self):
        config = self.config()
        path = API + "/devices/" + DEVICE_A
        error = HTTPError(config.manager_url + path, 404, "Not Found", {}, None)
        with patch.object(seed, "urlopen", side_effect=error) as network:
            result = seed.ApiClient(config).manager("GET", path, allow_missing=True)
        self.assertIsNone(result)
        self.assertEqual(network.call_count, 1)
        with patch.object(seed, "urlopen", return_value=HttpResponse(config.manager_url + path, None)):
            with self.assertRaises(seed.InitializationError):
                seed.ApiClient(config).manager("GET", path, allow_missing=True)

    def test_http_error_and_connection_failure_do_not_retry_posts(self):
        config = self.config()
        path = API + "/devices"
        for failure in (
            HTTPError(config.manager_url + path, 502, "Bad Gateway", {}, None),
            URLError("connection refused"),
            TimeoutError("timed out"),
        ):
            with self.subTest(failure=type(failure).__name__), patch.object(
                seed, "urlopen", side_effect=failure
            ) as network:
                with self.assertRaises(seed.InitializationError):
                    seed.ApiClient(config).manager("POST", path, {"id": DEVICE_A})
                self.assertEqual(network.call_count, 1)

    def test_download_rejects_external_image_urls_before_network(self):
        config = self.config()
        for path in ("https://outside.example/image.jpg", "/validation/media/../private.bin"):
            with self.subTest(path=path), patch.object(seed, "urlopen") as network:
                with self.assertRaises(seed.InitializationError):
                    seed.ApiClient(config).download(path)
                network.assert_not_called()


if __name__ == "__main__":
    unittest.main()
