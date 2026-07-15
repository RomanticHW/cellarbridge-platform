#!/usr/bin/env python3
from __future__ import annotations
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from scripts import validate_migration_history as history
SCRIPT = Path(__file__).with_name("validate_migration_history.py")
MIGRATION_DIR = "db/migration space[1]"
MIGRATION = f"{MIGRATION_DIR}/V7__quotation history.sql"
class MigrationHistoryGateTest(unittest.TestCase):
    @contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.git(root, "init", "-q", "-b", "main")
            self.git(root, "config", "user.name", "Migration Gate Test")
            self.git(root, "config", "user.email", "migration-gate@example.invalid")
            self.write(root, MIGRATION, "CREATE TABLE quotation.revision(id uuid);\n")
            self.write(root, "migration-ownership.csv", "V7,original-hash\n")
            yield root, self.commit(root, "base")
    def test_rejects_every_published_migration_change(self):
        expected = dict(modify="modified", delete="deleted", rename="renamed", copy="copied",
                        type="type-or-mode-changed", new_executable="new-migration-not-regular",
                        new_symlink="new-migration-not-regular", invalid_name="invalid-versioned-name")
        for change, violation in expected.items():
            with self.subTest(change=change), self.fixture() as (root, base):
                self.mutate(root, change)
                head = self.commit(root, change)
                result = self.gate(root, base, head)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn(violation, result.stderr)
                self.assertNotIn("CREATE TABLE", result.stderr)
    def test_allows_unrelated_source_and_a_higher_new_migration(self):
        with self.fixture() as (root, base):
            self.write(root, "src/A source [draft].java", "final class A {}\n")
            self.assertEqual(self.gate(root, base, self.commit(root, "source")).returncode, 0)
            self.write(root, f"{MIGRATION_DIR}/V10__inventory_unit.sql", "SELECT 1;\n")
            self.assertEqual(self.gate(root, base, self.commit(root, "migration")).returncode, 0)
    def test_rejects_invalid_new_versions_and_refs(self):
        with self.fixture() as (root, base):
            self.write(root, f"{MIGRATION_DIR}/V7__duplicate.sql", "SELECT 2;\n")
            head = self.commit(root, "duplicate")
            self.assertIn("version-not-higher", self.gate(root, base, head).stderr)
            for label, bad_base, bad_head in (
                ("base", "missing-ref", head), ("base", "0" * 40, head),
                ("head", base, "missing-ref"), ("head", base, "0" * 40)):
                result = self.gate(root, bad_base, bad_head)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(f"invalid-{label}-ref", result.stderr)
            self.assertIn("equal-refs", self.gate(root, base, base).stderr)
    def test_uses_merge_base_and_rejects_malformed_diff_records(self):
        with self.fixture() as (root, base):
            self.git(root, "switch", "-q", "-c", "feature")
            self.write(root, "feature.txt", "feature\n")
            head = self.commit(root, "feature")
            self.git(root, "switch", "-q", "main")
            self.write(root, MIGRATION, "SELECT 77;\n")
            self.assertEqual(self.gate(root, self.commit(root, "main"), head).returncode, 0)
        for fields in ([b"Z", b"path"], [b"R100", b"only"]):
            with self.assertRaises(history.GateError):
                history.parse_changes(fields)
    def mutate(self, root: Path, change: str) -> None:
        migration = root / MIGRATION
        if change == "modify":
            migration.write_text("SELECT 7;\n", encoding="utf-8")
            (root / "migration-ownership.csv").write_text("V7,replacement-hash\n", encoding="utf-8")
        elif change == "delete":
            migration.unlink()
        elif change == "rename":
            migration.rename(migration.with_name("V7__renamed.sql"))
        elif change == "copy":
            shutil.copy2(migration, root / "archive V7 [published].sql")
        elif change == "type":
            migration.unlink()
            migration.symlink_to("historical-target.sql")
        elif change == "new_executable":
            self.write(root, f"{MIGRATION_DIR}/V10__executable.sql", "SELECT 10;\n")
            (root / MIGRATION_DIR / "V10__executable.sql").chmod(0o755)
        elif change == "new_symlink":
            (root / MIGRATION_DIR / "V10__symlink.sql").symlink_to("missing-target.sql")
        elif change == "invalid_name":
            self.write(root, f"{MIGRATION_DIR}/V10_invalid.sql", "SELECT 10;\n")
    def gate(self, root: Path, base: str, head: str) -> subprocess.CompletedProcess[str]:
        command = [sys.executable, str(SCRIPT)]
        for option, value in (("base", base), ("head", head), ("migration-dir", MIGRATION_DIR)):
            command.extend((f"--{option}", value))
        return subprocess.run(command, cwd=root, text=True, capture_output=True, check=False)
    def commit(self, root: Path, message: str) -> str:
        self.git(root, "add", "-A")
        self.git(root, "commit", "-q", "-m", message)
        return self.git(root, "rev-parse", "HEAD").stdout.strip()
    def write(self, root: Path, relative: str, content: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    def git(self, root: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["git", *arguments], cwd=root, text=True, capture_output=True, check=True)
if __name__ == "__main__":
    unittest.main()
