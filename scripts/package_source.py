#!/usr/bin/env python3
"""Create or verify the PocketAgent source bundle without carrying stale build output."""

from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ARCHIVE = ROOT / "PocketAgent.zip"
ARCHIVE_ROOT = "PocketAgent"
EXCLUDED_PARTS = {
    ".cache",
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    "__pycache__",
    "build",
    "coverage",
    "dist",
    "node_modules",
    "out",
    "target",
}
EXCLUDED_NAMES = {"local.properties", "PocketAgent.zip", ".env", ".env.local", ".netrc"}
EXCLUDED_SUFFIXES = {".apk", ".log", ".class", ".pyc", ".jks", ".keystore", ".pem", ".key", ".p12", ".pfx"}


def application_sources() -> list[Path]:
    source_root = ROOT / "app" / "src" / "main"
    return sorted(path for path in source_root.rglob("*") if path.is_file())


def verify_manual_only_source() -> None:
    app_root = ROOT / "app" / "src" / "main"
    if not app_root.exists():
        raise RuntimeError("App source directory is missing.")

    forbidden_files = {"AgentService.kt", "accessibility_config.xml"}
    found = [path for path in application_sources() if path.name in forbidden_files]
    if found:
        names = ", ".join(str(path.relative_to(ROOT)) for path in found)
        raise RuntimeError(f"Refusing to package legacy accessibility files: {names}")

    forbidden_code = re.compile(
        r"class\s+\w+\s*:\s*(?:[\w.]+\.)?AccessibilityService\b"
        r"|\bperformGlobalAction\s*\("
        r"|\bAccessibilityNodeInfo\b"
        r"|\bTYPE_ACCESSIBILITY_OVERLAY\b"
    )
    for path in application_sources():
        if path.suffix not in {".kt", ".java", ".xml"}:
            continue
        content = path.read_text(encoding="utf-8")
        if forbidden_code.search(content):
            raise RuntimeError(
                f"Refusing to package active accessibility-control code in {path.relative_to(ROOT)}"
            )


def source_files() -> list[Path]:
    paths: list[Path] = []
    for path in ROOT.rglob("*"):
        relative = path.relative_to(ROOT)
        if not path.is_file() or path.is_symlink():
            continue
        if any(part in EXCLUDED_PARTS for part in relative.parts):
            continue
        if path.name in EXCLUDED_NAMES or path.name.startswith(".env.") or path.suffix.lower() in EXCLUDED_SUFFIXES:
            continue
        paths.append(path)
    return sorted(paths, key=lambda item: item.relative_to(ROOT).as_posix())


def archive_name(path: Path) -> str:
    return f"{ARCHIVE_ROOT}/{path.relative_to(ROOT).as_posix()}"


def create_archive() -> None:
    verify_manual_only_source()
    files = source_files()
    with zipfile.ZipFile(ARCHIVE, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for path in files:
            bundle.write(path, archive_name(path))
    print(f"Created {ARCHIVE.name} with {len(files)} current project files.")


def check_archive() -> None:
    verify_manual_only_source()
    expected = {archive_name(path): path.read_bytes() for path in source_files()}
    if not ARCHIVE.is_file():
        raise RuntimeError(f"{ARCHIVE.name} is missing; run this script without --check to regenerate it.")

    with zipfile.ZipFile(ARCHIVE, "r") as bundle:
        actual_names = set(bundle.namelist())
        expected_names = set(expected)
        missing = sorted(expected_names - actual_names)
        stale = sorted(actual_names - expected_names)
        if missing or stale:
            details = []
            if missing:
                details.append(f"missing: {', '.join(missing[:8])}")
            if stale:
                details.append(f"stale/unexpected: {', '.join(stale[:8])}")
            raise RuntimeError("Source archive contents differ from the tree (" + "; ".join(details) + ").")
        changed = [name for name, content in expected.items() if bundle.read(name) != content]
        if changed:
            raise RuntimeError(
                "Source archive has outdated files: " + ", ".join(changed[:8])
            )
    print(f"Verified {ARCHIVE.name}: {len(expected)} files match current manual-only source.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify that the existing archive matches the current safe source tree",
    )
    args = parser.parse_args()
    try:
        if args.check:
            check_archive()
        else:
            create_archive()
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"Source archive error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
