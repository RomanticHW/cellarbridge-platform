#!/usr/bin/env python3
from __future__ import annotations
import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import PurePosixPath
VERSIONED = re.compile(r"^V([1-9][0-9]*)__[^/]+\.sql$")
class GateError(RuntimeError): pass
def git(root: str | None, *arguments: str) -> bytes:
    result = subprocess.run(
        ["git", *arguments], cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False
    )
    if result.returncode:
        raise GateError("git-command-failed")
    return result.stdout
def resolve(root: str, label: str, ref: str) -> str:
    try:
        value = git(root, "rev-parse", "--verify", "--end-of-options", f"{ref}^{{commit}}")
    except GateError as error:
        raise GateError(f"invalid-{label}-ref") from error
    return value.decode("ascii").removesuffix("\n")
def version(path: str, directory: str) -> int | None:
    prefix = directory + "/"
    relative = path[len(prefix) :] if path.startswith(prefix) else ""
    match = VERSIONED.fullmatch(relative) if "/" not in relative else None
    return int(match.group(1)) if match else None

def tree(root: str, ref: str, directory: str) -> dict[str, tuple[str, str, str]]:
    entries = {}
    for record in git(root, "ls-tree", "-rz", ref, "--", directory).split(b"\0"):
        if record:
            metadata, path = record.split(b"\t", 1)
            mode, kind, oid = metadata.decode("ascii").split()
            entries[os.fsdecode(path)] = (mode, kind, oid)
    return entries
def parse_changes(fields: list[bytes]) -> list[tuple[str, list[str]]]:
    if fields and not fields[-1]:
        fields.pop()
    parsed, index = [], 0
    while index < len(fields):
        try:
            status = fields[index].decode("ascii")
        except UnicodeDecodeError as error:
            raise GateError("malformed-diff-status") from error
        index += 1
        if not re.fullmatch(r"(?:[ACDMRT]|[CR][0-9]{1,3})", status):
            raise GateError("unknown-diff-status")
        count = 2 if status[0] in "CR" else 1
        if index + count > len(fields):
            raise GateError("malformed-diff-record")
        parsed.append((status[0], [os.fsdecode(value) for value in fields[index : index + count]]))
        index += count
    return parsed
def changes(root: str, base: str, head: str) -> list[tuple[str, list[str]]]:
    output = git(root, "diff", "--name-status", "-z", "--find-renames=100%",
                 "--find-copies=100%", "--find-copies-harder", f"{base}...{head}")
    return parse_changes(output.split(b"\0"))

def validate(root: str, base: str, head: str, directory: str) -> list[tuple[str, str]]:
    if base == head:
        raise GateError("equal-refs")
    anchors = git(root, "merge-base", "--all", base, head).decode("ascii").splitlines()
    if len(anchors) != 1:
        raise GateError("missing-or-ambiguous-merge-base")
    anchor_tree, head_tree = tree(root, anchors[0], directory), tree(root, head, directory)
    old = {path: entry for path, entry in anchor_tree.items() if version(path, directory) is not None}
    highest = max((version(path, directory) or 0 for path in old), default=0)
    violations: list[tuple[str, str]] = []
    labels = {"M": "modified", "D": "deleted", "T": "type-or-mode-changed", "R": "renamed", "C": "copied"}
    for kind, paths in changes(root, base, head):
        source, destination = paths[0], paths[-1]
        if source in old and kind in labels:
            detail = f"{source} -> {destination}" if kind in "RC" else source
            violations.append((labels[kind], detail))
        if kind not in "ACR" or destination in old or not destination.startswith(directory + "/"):
            continue
        candidate = destination.rsplit("/", 1)[-1]
        new_version = version(destination, directory)
        if candidate.startswith("V") and candidate.endswith(".sql") and new_version is None:
            violations.append(("invalid-versioned-name", destination))
        elif new_version is not None and head_tree.get(destination, ())[:2] != ("100644", "blob"):
            violations.append(("new-migration-not-regular", destination))
        elif new_version is not None and new_version <= highest:
            violations.append(("version-not-higher", destination))
    for path, entry in old.items():
        if entry[:2] != ("100644", "blob"):
            violations.append(("baseline-not-regular", path))
        if head_tree.get(path) != entry:
            violations.append(("object-changed", path))
    return sorted(set(violations))
def main() -> int:
    parser = argparse.ArgumentParser()
    for name in ("base", "head", "migration-dir"):
        parser.add_argument(f"--{name}", required=True)
    arguments = parser.parse_args()
    directory = PurePosixPath(arguments.migration_dir)
    if directory.is_absolute() or ".." in directory.parts or str(directory) in ("", "."):
        print("ERROR migration-history invalid-migration-dir", file=sys.stderr)
        return 2
    try:
        root = os.fsdecode(git(None, "rev-parse", "--show-toplevel").removesuffix(b"\n"))
        base = resolve(root, "base", arguments.base)
        head = resolve(root, "head", arguments.head)
        violations = validate(root, base, head, directory.as_posix())
    except GateError as error:
        print(f"ERROR migration-history {error}", file=sys.stderr)
        return 2
    for kind, path in violations:
        print(f"ERROR migration-history {kind}: {json.dumps(path, ensure_ascii=True)}", file=sys.stderr)
    if violations:
        return 1
    print(f"PASS migration-history base={base} head={head} directory={json.dumps(directory.as_posix())}")
    return 0
if __name__ == "__main__":
    raise SystemExit(main())
