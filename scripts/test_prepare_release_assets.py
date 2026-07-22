from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class PrepareReleaseAssetsTest(unittest.TestCase):
    def test_manifest_and_checksums_match_every_payload(self) -> None:
        target = ROOT / "target"
        target.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=target) as directory:
            output = Path(directory) / "release"
            subprocess.run(
                ["python3", "scripts/prepare_release_assets.py", "--output", str(output)],
                cwd=ROOT,
                check=True,
            )
            manifest = json.loads((output / "release-manifest.json").read_text())
            self.assertEqual(manifest["release"], "1.0.0")
            self.assertEqual(manifest["compatibility"]["flywayMigration"], "22")
            checksum_rows = {
                name: digest
                for digest, name in (
                    line.split("  ", 1) for line in (output / "SHA256SUMS").read_text().splitlines()
                )
            }
            for name, expected in checksum_rows.items():
                actual = hashlib.sha256((output / name).read_bytes()).hexdigest()
                self.assertEqual(actual, expected)


if __name__ == "__main__":
    unittest.main()
