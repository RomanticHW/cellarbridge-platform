#!/usr/bin/env python3
"""Validate the release identity and assemble deterministic small release assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.0.0"
MAVEN_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git(*args: str) -> str:
    return subprocess.check_output(["git", "-C", ROOT, *args], text=True).strip()


def require_release_identity() -> None:
    root_version = ET.parse(ROOT / "pom.xml").getroot().findtext("m:version", namespaces=MAVEN_NAMESPACE)
    backend_parent_version = ET.parse(ROOT / "backend/pom.xml").getroot().findtext(
        "m:parent/m:version", namespaces=MAVEN_NAMESPACE
    )
    frontend_version = json.loads((ROOT / "frontend/package.json").read_text())["version"]
    values = {
        "root Maven version": root_version,
        "backend parent version": backend_parent_version,
        "frontend version": frontend_version,
    }
    mismatches = [f"{name}={value}" for name, value in values.items() if value != VERSION]
    if mismatches:
        raise SystemExit("Release identity mismatch: " + ", ".join(mismatches))
    changelog = (ROOT / "CHANGELOG.md").read_text()
    if f"## [{VERSION}] - 2026-07-22" not in changelog:
        raise SystemExit(f"CHANGELOG.md has no final {VERSION} section")


def copy_payload(source: Path, output: Path, name: str | None = None) -> Path:
    destination = output / (name or source.name)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    return destination


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "target/release-assets")
    parser.add_argument("--security-dir", type=Path)
    parser.add_argument("--screenshots-dir", type=Path)
    args = parser.parse_args()

    require_release_identity()
    output = args.output.resolve()
    permitted_root = (ROOT / "target").resolve()
    if output != permitted_root and permitted_root not in output.parents:
        raise SystemExit("Release output must remain under target/")
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    payloads = [
        copy_payload(
            ROOT / "docs/05-delivery/25-release-notes-v1.0.0.md",
            output,
            "RELEASE_NOTES.md",
        ),
        copy_payload(
            ROOT / "docs/evidence/performance/measured-latency.svg",
            output,
        ),
        copy_payload(ROOT / "docs/evidence/performance/report.md", output, "performance-report.md"),
        copy_payload(ROOT / "docs/evidence/resilience/report.md", output, "resilience-report.md"),
        copy_payload(ROOT / "docs/evidence/security/scan-summary.md", output, "security-scan-summary.md"),
    ]

    if args.security_dir is not None:
        for source in sorted(args.security_dir.glob("*")):
            if source.is_file() and source.suffix == ".json":
                payloads.append(copy_payload(source, output, f"security-{source.name}"))
    if args.screenshots_dir is not None:
        for source in sorted(args.screenshots_dir.glob("*.png")):
            payloads.append(copy_payload(source, output, f"screenshot-{source.name}"))

    manifest = {
        "schemaVersion": 1,
        "release": VERSION,
        "tag": f"v{VERSION}",
        "commit": git("rev-parse", "HEAD"),
        "tree": git("rev-parse", "HEAD^{tree}"),
        "compatibility": {
            "java": "21",
            "node": "24",
            "postgresql": "18.4",
            "flywayMigration": "22",
            "openapi": "1.13.0",
            "asyncapi": "1.5.0",
        },
        "artifacts": [
            {"file": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)}
            for path in sorted(payloads)
        ],
    }
    manifest_path = output / "release-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    checksum_paths = sorted([*payloads, manifest_path])
    (output / "SHA256SUMS").write_text(
        "".join(f"{sha256(path)}  {path.name}\n" for path in checksum_paths)
    )
    print(f"Prepared {len(checksum_paths) + 1} release files in {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
