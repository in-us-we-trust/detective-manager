"""Pruebas del envío multipart y de la evidencia, sin servicios externos."""

from contextlib import redirect_stdout, redirect_stderr
from email import policy
from email.parser import BytesParser
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError


SCRIPT = Path(__file__).resolve().parents[1] / "send-validation-event.py"
SPEC = importlib.util.spec_from_file_location("send_validation_event", SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class ValidationEventRunnerTest(unittest.TestCase):
    def test_multipart_keeps_binary_payload_filename_and_contract_fields(self):
        payload = b"\x00\xff\r\nvideo\x80"
        fields = {"trackId": "visit", "deviceId": "doorbell", "mediaType": "VIDEO"}
        boundary, body = runner.build_multipart(fields, "chunk_004.mp4", payload)
        message = BytesParser(policy=policy.default).parsebytes(
            ("Content-Type: multipart/form-data; boundary=" + boundary + "\r\n\r\n")
            .encode("ascii") + body
        )
        parts = list(message.iter_parts())
        received_fields = {
            part.get_param("name", header="content-disposition"): part.get_payload(decode=True)
            for part in parts[:-1]
        }
        self.assertEqual(received_fields, {key: value.encode() for key, value in fields.items()})
        self.assertEqual(parts[-1].get_filename(), "chunk_004.mp4")
        self.assertEqual(parts[-1].get_content_type(), "video/mp4")
        self.assertEqual(parts[-1].get_payload(decode=True), payload)

    def test_infers_all_supported_media_types_and_rejects_unknown(self):
        for media_type, extensions in runner.EXTENSIONS.items():
            for extension in extensions:
                with self.subTest(extension=extension):
                    self.assertEqual(runner.infer_media_type(Path("file" + extension.upper())), media_type)
        with self.assertRaisesRegex(ValueError, "--media-type"):
            runner.infer_media_type(Path("unknown.dat"))

    def test_http_error_is_saved_with_status_body_and_source_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "audio.wav"
            path.write_bytes(b"audio content")
            response = b'{"message":"ML unavailable"}'
            error = HTTPError("http://localhost/events", 502, "Bad Gateway", {}, io.BytesIO(response))
            with patch.object(runner, "urlopen", side_effect=error), redirect_stdout(io.StringIO()):
                result = runner.main(["--file", str(path), "--output-dir", directory])
            self.assertEqual(result, 1)
            saved = json.loads(next(Path(directory).glob("*.json")).read_text())
            self.assertEqual(saved["httpStatus"], 502)
            self.assertEqual(saved["response"], {"message": "ML unavailable"})
            self.assertEqual(saved["input"]["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertEqual(saved["request"]["mediaType"], "AUDIO")
            self.assertGreaterEqual(saved["elapsedMs"], 0)
            self.assertIn("+00:00", saved["startedAt"])
            self.assertIn("+00:00", saved["finishedAt"])

    def test_network_failure_is_saved_with_null_status(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "image.png"
            path.write_bytes(b"image content")
            with patch.object(runner, "urlopen", side_effect=URLError("connection refused")), \
                    redirect_stdout(io.StringIO()):
                result = runner.main(["--file", str(path), "--output-dir", directory])
            self.assertEqual(result, 1)
            saved = json.loads(next(Path(directory).glob("*.json")).read_text())
            self.assertIsNone(saved["httpStatus"])
            self.assertIsNone(saved["response"])
            self.assertIn("connection refused", saved["error"])

    def test_created_response_is_recorded_without_claiming_analysis_success(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "clip.mp4"
            path.write_bytes(b"video")
            response = unittest.mock.MagicMock()
            response.__enter__.return_value = response
            response.status = 201
            response.read.return_value = b'{"data":{"message":"No se pudo completar el analisis"}}'
            with patch.object(runner, "urlopen", return_value=response), redirect_stdout(io.StringIO()):
                result = runner.main(["--file", str(path), "--output-dir", directory])
            self.assertEqual(result, 0)
            saved = json.loads(next(Path(directory).glob("*.json")).read_text())
            self.assertEqual(saved["httpStatus"], 201)
            self.assertIn("No se pudo", saved["response"]["data"]["message"])
            self.assertNotIn("passed", saved)

    def test_empty_file_and_invalid_uuid_do_not_send_requests(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "image.jpg"
            path.write_bytes(b"")
            for extra in ([], ["--device-id", "not-a-uuid"]):
                with self.subTest(extra=extra), patch.object(runner, "urlopen") as send, \
                        redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as error:
                        runner.main(["--file", str(path)] + extra)
                    self.assertEqual(error.exception.code, 2)
                    send.assert_not_called()


if __name__ == "__main__":
    unittest.main()
