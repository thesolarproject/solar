import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "y1-firmware-preflight.py"
SPEC = importlib.util.spec_from_file_location("y1_firmware_preflight", SCRIPT)
preflight = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(preflight)


class Y1FirmwarePreflightTest(unittest.TestCase):
    def test_partition_aliases_cover_mtk_and_scatter_names(self):
        expected = {
            "boot.img": "boot",
            "bootimg.bin": "boot",
            "lk.bin": "uboot",
            "UBOOT.bin": "uboot",
            "pro_info.bin": "pro_info",
            "SEC_RO.img": "sec_ro",
            "usrdata.img": "userdata",
        }
        for name, partition in expected.items():
            self.assertEqual(
                partition, preflight.normalized_partition_name(Path(name))
            )

    def test_detect_variant_requires_two_independent_matches(self):
        registry = {
            "variants": {
                "a": {
                    "partition_fingerprints": {
                        "boot": "AA",
                        "uboot": "AB",
                        "recovery": "AC",
                    }
                },
                "b": {
                    "partition_fingerprints": {
                        "boot": "BA",
                        "uboot": "BB",
                        "recovery": "BC",
                    }
                },
            }
        }
        one = {"boot": [{"sha256": "AA"}]}
        self.assertIsNone(preflight.detect_variant(one, registry)[0])
        two = {
            "boot": [{"sha256": "AA"}],
            "uboot": [{"sha256": "AB"}],
        }
        self.assertEqual("a", preflight.detect_variant(two, registry)[0])

    def test_detect_variant_refuses_mixed_evidence(self):
        registry = {
            "variants": {
                "a": {
                    "partition_fingerprints": {
                        "boot": "AA",
                        "uboot": "AB",
                    }
                },
                "b": {
                    "partition_fingerprints": {
                        "boot": "BA",
                        "recovery": "BC",
                    }
                },
            }
        }
        mixed = {
            "boot": [{"sha256": "AA"}],
            "uboot": [{"sha256": "AB"}],
            "recovery": [{"sha256": "BC"}],
        }
        self.assertIsNone(preflight.detect_variant(mixed, registry)[0])

    def test_manifest_creation_never_overwrites(self):
        registry = {"schema_version": 1, "variants": {"a": {}}}
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            output = root / "manifest.json"
            output.write_text("keep", encoding="utf-8")
            with self.assertRaises(preflight.PreflightError):
                preflight.create_backup_manifest(
                    root, output, "a", "", registry
                )
            self.assertEqual("keep", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
