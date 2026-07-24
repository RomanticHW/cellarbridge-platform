#!/usr/bin/env python3
"""Classify every release-tree file into one public publication category."""

from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs/evidence/release/tracked-file-inventory.tsv"


def repository_paths() -> list[str]:
    output = subprocess.check_output(
        ["git", "-C", ROOT, "ls-files", "--cached", "--others", "--exclude-standard"],
        text=True,
    )
    paths = {path for path in output.splitlines() if path and not path.startswith("target/")}
    paths.add(OUTPUT.relative_to(ROOT).as_posix())
    return sorted(paths)


def classify(path: str) -> str:
    name = Path(path).name
    if path.startswith("docs/evidence/"):
        return "evidence"
    if path.startswith("contracts/"):
        return "contract"
    if path.startswith("frontend/src/api/generated/"):
        return "generated"
    if (
        path.startswith("backend/src/test/")
        or path.startswith("keycloak-resource-binding/src/test/")
        or path.startswith("frontend/e2e/")
        or ".test." in name
        or name.startswith("test_")
    ):
        return "test"
    if (
        path.startswith("backend/src/main/")
        or path.startswith("keycloak-resource-binding/src/main/")
        or path.startswith("frontend/src/")
    ):
        return "source"
    if path.startswith("docs/") or name in {
        "README.md",
        "AGENTS.md",
        "CHANGELOG.md",
        "CODE_OF_CONDUCT.md",
        "CONTRIBUTING.md",
        "LICENSE",
        "NOTICE",
        "SECURITY.md",
    }:
        return "doc"
    return "release_asset"


def render() -> str:
    rows = ["path\tcategory"]
    rows.extend(f"{path}\t{classify(path)}" for path in repository_paths())
    return "\n".join(rows) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    expected = render()
    if args.write:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(expected)
        print(f"Wrote {len(expected.splitlines()) - 1} classified paths to {OUTPUT.relative_to(ROOT)}")
        return 0
    if not OUTPUT.exists() or OUTPUT.read_text() != expected:
        raise SystemExit("Publication inventory is stale; run scripts/generate_publication_inventory.py --write")
    print(f"PASS: publication inventory covers {len(expected.splitlines()) - 1} paths")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
