#!/usr/bin/env python3
"""Generate versioned, deterministic, compressed synthetic evidence data."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import uuid
from pathlib import Path
from typing import Callable, Iterator

PROFILE_SCHEMA = "cellarbridge.performance-profile.v1"
MANIFEST_SCHEMA = "cellarbridge.synthetic-dataset.v1"
GENERATOR_VERSION = "1.1.0"
NAMESPACE = uuid.UUID("c48f41d4-1633-46bd-8da8-33d32c91eb3f")


def stable_id(seed: int, entity: str, index: int) -> str:
    return str(uuid.uuid5(NAMESPACE, f"{seed}:{entity}:{index}"))


def products(seed: int, count: int, _: dict[str, int]) -> Iterator[dict[str, object]]:
    categories = ("RED", "WHITE", "ROSE", "SPARKLING", "FORTIFIED", "DESSERT")
    for index in range(1, count + 1):
        yield {
            "id": stable_id(seed, "product", index),
            "number": f"PERF-PRODUCT-{index:06d}",
            "category": categories[index % len(categories)],
            "producerNumber": (index - 1) % 100 + 1,
            "regionNumber": (index - 1) % 50 + 1,
        }


def skus(seed: int, count: int, counts: dict[str, int]) -> Iterator[dict[str, object]]:
    for index in range(1, count + 1):
        product_index = (index - 1) % counts["products"] + 1
        variant = (index - 1) // counts["products"] + 1
        yield {
            "id": stable_id(seed, "sku", index),
            "productId": stable_id(seed, "product", product_index),
            "code": f"PERF-{product_index:06d}-{variant:02d}",
            "quantityUnit": "CASE" if index % 3 else "BOTTLE",
        }


def inventory_lots(seed: int, count: int, counts: dict[str, int]) -> Iterator[dict[str, object]]:
    for index in range(1, count + 1):
        sku_index = (index - 1) % counts["skus"] + 1
        yield {
            "id": stable_id(seed, "inventory-lot", index),
            "skuId": stable_id(seed, "sku", sku_index),
            "warehouse": f"WH-{(index - 1) % 20 + 1:02d}",
            "quantityUnit": "CASE" if index % 4 else "BOTTLE",
            "onHandQuantity": 24 + index % 73,
            "reservedQuantity": index % 7,
        }


def partners(seed: int, count: int, _: dict[str, int]) -> Iterator[dict[str, object]]:
    for index in range(1, count + 1):
        yield {
            "id": stable_id(seed, "partner", index),
            "number": f"PERF-PARTNER-{index:05d}",
            "role": "BUYER" if index % 4 else "SUPPLIER",
            "country": ("CN", "SG", "FR", "IT", "AU")[index % 5],
        }


def quotations(seed: int, count: int, counts: dict[str, int]) -> Iterator[dict[str, object]]:
    for index in range(1, count + 1):
        partner_index = (index - 1) % counts["partners"] + 1
        yield {
            "id": stable_id(seed, "quotation", index),
            "number": f"PERF-QUOTE-{index:07d}",
            "partnerId": stable_id(seed, "partner", partner_index),
            "skuId": stable_id(seed, "sku", (index - 1) % counts["skus"] + 1),
            "quantity": index % 12 + 1,
            "status": ("DRAFT", "ISSUED", "ACCEPTED")[index % 3],
        }


def orders(seed: int, count: int, counts: dict[str, int]) -> Iterator[dict[str, object]]:
    for index in range(1, count + 1):
        quotation_index = (index - 1) % counts["quotations"] + 1
        yield {
            "id": stable_id(seed, "order", index),
            "number": f"PERF-ORDER-{index:07d}",
            "quotationId": stable_id(seed, "quotation", quotation_index),
            "status": ("CREATED", "ALLOCATED", "FULFILLING")[index % 3],
        }


def events(seed: int, count: int, counts: dict[str, int]) -> Iterator[dict[str, object]]:
    event_types = (
        "cellarbridge.quotation.accepted.v1",
        "cellarbridge.trade-order.created.v1",
        "cellarbridge.inventory.reserved.v1",
        "cellarbridge.fulfillment.milestone-recorded.v1",
    )
    for index in range(1, count + 1):
        order_index = (index - 1) % counts["orders"] + 1
        yield {
            "eventId": stable_id(seed, "event", index),
            "eventType": event_types[index % len(event_types)],
            "aggregateId": stable_id(seed, "order", order_index),
            "correlationId": stable_id(seed, "correlation", order_index),
            "sequence": index,
        }


GENERATORS: tuple[
    tuple[str, str, Callable[[int, int, dict[str, int]], Iterator[dict[str, object]]]], ...
] = (
    ("products", "products.jsonl.gz", products),
    ("skus", "skus.jsonl.gz", skus),
    ("inventoryLots", "inventory-lots.jsonl.gz", inventory_lots),
    ("partners", "partners.jsonl.gz", partners),
    ("quotations", "quotations.jsonl.gz", quotations),
    ("orders", "orders.jsonl.gz", orders),
    ("events", "events.jsonl.gz", events),
)


def load_profile(path: Path) -> dict[str, object]:
    profile = json.loads(path.read_text(encoding="utf-8"))
    if profile.get("schemaVersion") != PROFILE_SCHEMA:
        raise ValueError(f"unsupported profile schema: {profile.get('schemaVersion')}")
    if not isinstance(profile.get("seed"), int) or profile["seed"] <= 0:
        raise ValueError("profile seed must be a positive integer")
    counts = profile.get("counts")
    if not isinstance(counts, dict):
        raise ValueError("profile counts must be an object")
    for name, _, _ in GENERATORS:
        if not isinstance(counts.get(name), int) or counts[name] <= 0:
            raise ValueError(f"profile count {name} must be a positive integer")
    return profile


def write_jsonl_gzip(path: Path, records: Iterator[dict[str, object]]) -> tuple[int, str, int]:
    count = 0
    with path.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            for record in records:
                line = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
                compressed.write(line.encode("utf-8") + b"\n")
                count += 1
    payload = path.read_bytes()
    return count, hashlib.sha256(payload).hexdigest(), len(payload)


def generate(profile_path: Path, output_dir: Path) -> dict[str, object]:
    profile = load_profile(profile_path)
    output_dir.mkdir(parents=True, exist_ok=True)
    seed = int(profile["seed"])
    counts = {key: int(value) for key, value in dict(profile["counts"]).items()}
    files = []
    for count_name, filename, factory in GENERATORS:
        record_count, digest, size = write_jsonl_gzip(
            output_dir / filename, factory(seed, counts[count_name], counts)
        )
        if record_count != counts[count_name]:
            raise AssertionError(f"generated {record_count} {count_name}, expected {counts[count_name]}")
        files.append(
            {
                "name": filename,
                "recordType": count_name,
                "recordCount": record_count,
                "sha256": digest,
                "compressedBytes": size,
            }
        )
    canonical_profile = json.dumps(profile, sort_keys=True, separators=(",", ":")).encode()
    manifest = {
        "schemaVersion": MANIFEST_SCHEMA,
        "generatorVersion": GENERATOR_VERSION,
        "profile": profile["name"],
        "profileSha256": hashlib.sha256(canonical_profile).hexdigest(),
        "seed": seed,
        "counts": counts,
        "files": files,
    }
    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = generate(args.profile, args.output)
    print(json.dumps(manifest, sort_keys=True))


if __name__ == "__main__":
    main()
