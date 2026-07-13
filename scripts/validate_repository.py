#!/usr/bin/env python3
"""Validate the public CellarBridge design baseline without modifying files."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Iterable
from urllib.parse import unquote

try:
    import yaml
except ImportError as exc:  # pragma: no cover - actionable local failure
    raise SystemExit("PyYAML is required: python -m pip install 'PyYAML>=6,<7'") from exc

try:
    from jsonschema import Draft202012Validator, FormatChecker
    from referencing import Registry, Resource
except ImportError as exc:  # pragma: no cover - actionable local failure
    raise SystemExit(
        "jsonschema is required: python -m pip install 'jsonschema[format]>=4.23,<5'"
    ) from exc

ROOT = Path(__file__).resolve().parents[1]
MARKDOWN_LINK = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
FENCE = re.compile(r"^\s*```")
UNRESOLVED_MARKER = re.compile(r"\b(?:TODO|TBD|XXX)\s*:", re.IGNORECASE)
FORBIDDEN_PATH_PARTS = {
    "private-control",
    "private-control-repository",
    "project-control",
    "prompts-private",
    "research/raw",
}
FORBIDDEN_FILE_NAMES = {
    "HANDOFF.md",
    "task-ledger.yaml",
    "repository-map.yaml",
    "IMG_9943.png",
    "IMG_9944.png",
}
TEXT_SUFFIXES = {".md", ".yaml", ".yml", ".json", ".csv", ".txt", ".py", ".xml"}
IGNORED_PATH_PARTS = {
    ".git",
    ".pnpm-store",
    "coverage",
    "dist",
    "node_modules",
    "playwright-report",
    "target",
    "test-results",
}


def repository_files() -> list[Path]:
    return sorted(
        path
        for path in ROOT.rglob("*")
        if path.is_file() and not any(part in IGNORED_PATH_PARTS for part in path.parts)
    )


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def validate_common(files: Iterable[Path]) -> list[str]:
    errors: list[str] = []
    for path in files:
        rel = relative(path)
        if path.stat().st_size == 0:
            errors.append(f"empty file: {rel}")
        if path.name in FORBIDDEN_FILE_NAMES:
            errors.append(f"private-control artifact in public baseline: {rel}")
        lowered = rel.lower()
        if any(part in lowered for part in FORBIDDEN_PATH_PARTS):
            errors.append(f"forbidden public path: {rel}")
        if path.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".pdf", ".zip"}:
            errors.append(f"unreviewed binary artifact in public baseline: {rel}")
        if path.suffix.lower() in TEXT_SUFFIXES or path.name in {"Makefile", "LICENSE", "NOTICE"}:
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                errors.append(f"text file is not valid UTF-8: {rel}")
                continue
            for line_number, line in enumerate(text.splitlines(), start=1):
                if UNRESOLVED_MARKER.search(line):
                    errors.append(f"unresolved marker at {rel}:{line_number}: {line.strip()}")
                # High-signal secret patterns only. Do not print a captured secret value.
                if re.search(r"AKIA[0-9A-Z]{16}", line):
                    errors.append(f"possible AWS key at {rel}:{line_number}")
                if re.search(r"gh[pousr]_[A-Za-z0-9_]{30,}", line):
                    errors.append(f"possible GitHub token at {rel}:{line_number}")
                if re.search(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", line):
                    errors.append(f"private key material at {rel}:{line_number}")
    return errors


def strip_code_fences(text: str) -> str:
    visible: list[str] = []
    in_fence = False
    for line in text.splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if not in_fence:
            visible.append(line)
    return "\n".join(visible)


def validate_markdown(files: Iterable[Path]) -> list[str]:
    errors: list[str] = []
    for path in files:
        if path.suffix.lower() != ".md":
            continue
        text = path.read_text(encoding="utf-8")
        fence_count = sum(1 for line in text.splitlines() if FENCE.match(line))
        if fence_count % 2:
            errors.append(f"unclosed fenced code block: {relative(path)}")
        visible = strip_code_fences(text)
        for raw_target in MARKDOWN_LINK.findall(visible):
            target = raw_target.strip()
            if target.startswith("<") and target.endswith(">"):
                target = target[1:-1]
            # Strip an optional Markdown title and fragment.
            target = target.split(' "', 1)[0].split(" '", 1)[0]
            if not target or target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            target = unquote(target).split("#", 1)[0].split("?", 1)[0]
            if not target:
                continue
            resolved = (path.parent / target).resolve()
            try:
                resolved.relative_to(ROOT.resolve())
            except ValueError:
                errors.append(f"relative link escapes repository: {relative(path)} -> {target}")
                continue
            if not resolved.exists():
                errors.append(f"broken relative link: {relative(path)} -> {target}")
    return errors


def load_yaml_json(path: Path) -> object:
    if path.suffix.lower() == ".json":
        return json.loads(path.read_text(encoding="utf-8"))
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def collect_external_refs(value: object) -> list[str]:
    refs: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "$ref" and isinstance(child, str) and not child.startswith(("#", "http://", "https://")):
                refs.append(child.split("#", 1)[0])
            refs.extend(collect_external_refs(child))
    elif isinstance(value, list):
        for child in value:
            refs.extend(collect_external_refs(child))
    return refs


def resolve_local_json_pointer(document: object, ref: str) -> bool:
    if not ref.startswith("#/"):
        return True
    current = document
    for token in ref[2:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        if isinstance(current, dict) and token in current:
            current = current[token]
        elif isinstance(current, list) and token.isdigit() and int(token) < len(current):
            current = current[int(token)]
        else:
            return False
    return True


def collect_local_refs(value: object) -> list[str]:
    refs: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "$ref" and isinstance(child, str) and child.startswith("#/"):
                refs.append(child)
            refs.extend(collect_local_refs(child))
    elif isinstance(value, list):
        for child in value:
            refs.extend(collect_local_refs(child))
    return refs


def validate_contracts(files: Iterable[Path]) -> list[str]:
    errors: list[str] = []
    structured = [p for p in files if p.suffix.lower() in {".json", ".yaml", ".yml"}]
    documents: dict[Path, object] = {}
    for path in structured:
        try:
            document = load_yaml_json(path)
            documents[path] = document
        except Exception as exc:  # noqa: BLE001 - diagnostic aggregation
            errors.append(f"cannot parse {relative(path)}: {type(exc).__name__}: {exc}")
            continue
        for ref in collect_external_refs(document):
            ref_path = (path.parent / ref).resolve()
            if not ref_path.exists():
                errors.append(f"missing external $ref: {relative(path)} -> {ref}")
        for ref in collect_local_refs(document):
            if not resolve_local_json_pointer(document, ref):
                errors.append(f"missing local $ref: {relative(path)} -> {ref}")

    schema_dir = ROOT / "contracts" / "schemas" / "events"
    example_dir = ROOT / "contracts" / "examples" / "events"
    envelope_path = schema_dir / "event-envelope.schema.json"
    if not envelope_path.exists():
        errors.append("missing event-envelope.schema.json")
        return errors

    resources: list[tuple[str, Resource[object]]] = []
    for schema_path in schema_dir.glob("*.schema.json"):
        try:
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
            resource = Resource.from_contents(schema)
            resources.append((schema_path.as_uri(), resource))
            if isinstance(schema, dict) and isinstance(schema.get("$id"), str):
                resources.append((schema["$id"], resource))
        except Exception as exc:  # already reported above, keep validation moving
            errors.append(f"cannot prepare schema {relative(schema_path)}: {exc}")
    registry = Registry().with_resources(resources)

    for example_path in sorted(example_dir.glob("*.example.json")):
        schema_name = example_path.name.replace(".example.json", ".schema.json")
        schema_path = schema_dir / schema_name
        if not schema_path.exists():
            errors.append(f"event example has no matching schema: {relative(example_path)}")
            continue
        try:
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
            instance = json.loads(example_path.read_text(encoding="utf-8"))
            validator = Draft202012Validator(
                schema, registry=registry, format_checker=FormatChecker()
            )
            validation_errors = sorted(validator.iter_errors(instance), key=lambda err: list(err.path))
            for item in validation_errors:
                location = "/".join(str(segment) for segment in item.path) or "<root>"
                errors.append(
                    f"invalid event example {relative(example_path)} at {location}: {item.message}"
                )
        except Exception as exc:  # noqa: BLE001 - diagnostic aggregation
            errors.append(f"cannot validate {relative(example_path)}: {type(exc).__name__}: {exc}")
    return errors


def validate_required_layout() -> list[str]:
    required = [
        "README.md",
        "AGENTS.md",
        "CONTRIBUTING.md",
        "SECURITY.md",
        "CHANGELOG.md",
        "LICENSE",
        "NOTICE",
        "Makefile",
        "docs/design-baseline.yaml",
        "contracts/README.md",
        "contracts/openapi/cellarbridge-api.yaml",
        "contracts/asyncapi/cellarbridge-events.yaml",
        ".github/workflows/docs-check.yml",
        ".github/pull_request_template.md",
    ]
    errors = [f"missing required path: {item}" for item in required if not (ROOT / item).exists()]
    for directory in [
        "docs/00-research",
        "docs/01-product",
        "docs/02-domain",
        "docs/03-architecture",
        "docs/04-contracts",
        "docs/05-delivery",
    ]:
        if not (ROOT / directory).is_dir():
            errors.append(f"missing required directory: {directory}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--scope",
        choices=("all", "docs", "contracts", "public"),
        default="all",
        help="check a focused area; 'all' runs every check",
    )
    args = parser.parse_args()
    files = repository_files()
    errors: list[str] = []

    if args.scope in {"all", "public"}:
        errors.extend(validate_required_layout())
        errors.extend(validate_common(files))
    if args.scope in {"all", "docs"}:
        errors.extend(validate_markdown(files))
    if args.scope in {"all", "contracts"}:
        errors.extend(validate_contracts(files))

    if errors:
        print(f"FAILED: {len(errors)} issue(s) found", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(
        f"PASS: scope={args.scope}; files={len(files)}; "
        f"markdown={sum(p.suffix.lower() == '.md' for p in files)}; "
        f"structured={sum(p.suffix.lower() in {'.json', '.yaml', '.yml'} for p in files)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
