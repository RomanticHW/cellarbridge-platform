from __future__ import annotations

import gzip
import json
import tempfile
import unittest
from pathlib import Path

from performance.generate_dataset import generate


class GenerateDatasetTest(unittest.TestCase):
    def test_generation_is_deterministic_and_preserves_requested_counts(self) -> None:
        profile = {
            "schemaVersion": "cellarbridge.performance-profile.v1",
            "name": "test",
            "seed": 17,
            "counts": {
                "products": 2,
                "skus": 6,
                "inventoryLots": 8,
                "partners": 4,
                "quotations": 5,
                "orders": 3,
                "events": 11,
            },
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            profile_path = root / "profile.json"
            profile_path.write_text(json.dumps(profile), encoding="utf-8")
            first = generate(profile_path, root / "first")
            second = generate(profile_path, root / "second")

            self.assertEqual(first, second)
            self.assertEqual(first["counts"], profile["counts"])
            self.assertEqual(
                [entry["recordCount"] for entry in first["files"]],
                [2, 6, 8, 4, 5, 3, 11],
            )
            with gzip.open(root / "first/partners.jsonl.gz", "rt", encoding="utf-8") as source:
                partner_ids = {json.loads(line)["id"] for line in source}
            with gzip.open(root / "first/quotations.jsonl.gz", "rt", encoding="utf-8") as source:
                quotation_partner_ids = {json.loads(line)["partnerId"] for line in source}
            self.assertTrue(quotation_partner_ids.issubset(partner_ids))


if __name__ == "__main__":
    unittest.main()
